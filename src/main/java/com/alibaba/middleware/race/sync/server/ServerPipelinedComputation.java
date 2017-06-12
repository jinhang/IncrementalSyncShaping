package com.alibaba.middleware.race.sync.server;

import com.alibaba.middleware.race.sync.Server;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Created by yche on 6/8/17.
 */
public class ServerPipelinedComputation {
    // input parameters
    private static long pkLowerBound;
    private static long pkUpperBound;

    public static void initRange(long lowerBound, long upperBound) {
        pkLowerBound = lowerBound;
        pkUpperBound = upperBound;
    }

    public static void initSchemaTable(String schema, String table) {
        RecordLazyEval.schema = schema;
        RecordLazyEval.table = table;
    }

    static boolean isKeyInRange(long key) {
        return pkLowerBound < key && key < pkUpperBound;
    }

    // intermediate result
    final static Map<Long, RecordUpdate> inRangeActiveKeys = new HashMap<>();
    final static Set<Long> outOfRangeActiveKeys = new HashSet<>();
    final static Set<Long> deadKeys = new HashSet<>();

    // final result
    static ArrayList<String> filedList = new ArrayList<>();
    public final static Map<Long, String> inRangeRecord = new TreeMap<>();

    // sequential computation model
    private static SequentialRestore sequentialRestore = new SequentialRestore();

    // type1 pool: decode
    private final static ExecutorService decodeDispatchMediatorPool = Executors.newSingleThreadExecutor();
    private final static int DECODE_WORKER_NUM = 8;
    private final static ExecutorService decodePool = Executors.newFixedThreadPool(DECODE_WORKER_NUM);

    // type2 pool: transform and computation
    private final static ExecutorService transCompMediatorPool = Executors.newSingleThreadExecutor();
    private final static int TRANSFORM_WORKER_NUM = 8;
    private final static ExecutorService transformPool = Executors.newFixedThreadPool(TRANSFORM_WORKER_NUM);
    private final static ExecutorService computationPool = Executors.newSingleThreadExecutor();

    // co-routine: read files into page cache
    private final static ExecutorService pageCachePool = Executors.newSingleThreadExecutor();

    public static void readFilesIntoPageCache(final ArrayList<String> fileList) throws IOException {
        pageCachePool.execute(new Runnable() {
            @Override
            public void run() {
                long startTime = System.currentTimeMillis();

                for (String filePath : fileList) {
                    try {
                        FileUtil.readFileIntoPageCache(filePath);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                long endTime = System.currentTimeMillis();
                System.out.println("read files into page cache, cost: " + (endTime - startTime) + "ms");
                if (Server.logger != null) {
                    Server.logger.info("read files into page cache, cost: " + (endTime - startTime) + "ms");
                }
            }
        });
    }

    // task buffer: ByteArrTaskBuffer, StringTaskBuffer, RecordLazyEvalTaskBuffer
    public static class ByteArrTaskBuffer {
        static int MAX_SIZE = 100000; // 0.1M
        private byte[][] byteArrArr = new byte[MAX_SIZE][];    // 100B*0.1M=10MB
        private int nextIndex = 0;

        private void addData(byte[] line) {
            byteArrArr[nextIndex] = line;
            nextIndex++;
        }

        boolean isFull() {
            return nextIndex >= MAX_SIZE;
        }

        public int length() {
            return nextIndex;
        }

        public byte[] get(int idx) {
            return byteArrArr[idx];
        }
    }

    public static class StringTaskBuffer {
        private String[] stringArr;
        private int nextIndex = 0;

        StringTaskBuffer(int size) {
            stringArr = new String[size];
        }

        private void addData(String line) {
            stringArr[nextIndex] = line;
            nextIndex++;
        }

        public int length() {
            return nextIndex;
        }

        public String get(int idx) {
            return stringArr[idx];
        }
    }

    public static class RecordLazyEvalTaskBuffer {
        final private RecordLazyEval[] recordLazyEvals;
        int nextIndex = 0;

        RecordLazyEvalTaskBuffer(int taskSize) {
            this.recordLazyEvals = new RecordLazyEval[taskSize];
        }

        void addData(RecordLazyEval recordLazyEval) {
            recordLazyEvals[nextIndex] = recordLazyEval;
            nextIndex++;
        }

        public int length() {
            return nextIndex;
        }

        public RecordLazyEval get(int idx) {
            return recordLazyEvals[idx];
        }
    }

    // tasks type1: DecodeDispatchTask, DecodeTask,
    // tasks type2: TransCompMediatorTask, TransformTask, ComputationTask
    private static class DecodeDispatchTask implements Runnable {
        private ByteArrTaskBuffer taskBuffer;
        private FindResultListener findResultListener;
        private static Queue<Future<StringTaskBuffer>> stringTaskQueue = new LinkedList<>();

        DecodeDispatchTask(ByteArrTaskBuffer taskBuffer, FindResultListener findResultListener) {
            this.taskBuffer = taskBuffer;
            this.findResultListener = findResultListener;
        }

        static void syncConsumeReadyJobs(final FindResultListener findResultListener) {
            while (!stringTaskQueue.isEmpty()) {
                Future<StringTaskBuffer> stringTaskBufferFuture = stringTaskQueue.poll();
                StringTaskBuffer strTaskBuffer = null;
                while (strTaskBuffer == null) {
                    try {
                        strTaskBuffer = stringTaskBufferFuture.get();
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                    }
                }
                transCompMediatorPool.execute(new TransCompMediatorTask(strTaskBuffer, findResultListener));
            }

            transCompMediatorPool.execute(new Runnable() {
                @Override
                public void run() {
                    TransCompMediatorTask.syncConsumeReadyJobs(findResultListener);
                }
            });
        }

        @Override
        public void run() {
            try {
                while (stringTaskQueue.size() > 0) {
                    Future<StringTaskBuffer> futureWork = stringTaskQueue.peek();
                    if (futureWork.isDone()) {
                        transCompMediatorPool.execute(new TransCompMediatorTask(futureWork.get(), findResultListener));
                        stringTaskQueue.poll();
                    } else {
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            Future<StringTaskBuffer> strTaskBufferFuture = decodePool.submit(new DecodeTask(taskBuffer));
            stringTaskQueue.add(strTaskBufferFuture);
        }
    }

    private static class DecodeTask implements Callable<StringTaskBuffer> {
        private ByteArrTaskBuffer byteArrTaskBuffer;

        DecodeTask(ByteArrTaskBuffer byteArrTaskBuffer) {
            this.byteArrTaskBuffer = byteArrTaskBuffer;
        }

        @Override
        public StringTaskBuffer call() throws Exception {
            StringTaskBuffer stringTaskBuffer = new StringTaskBuffer(byteArrTaskBuffer.length());
            for (int i = 0; i < byteArrTaskBuffer.length(); i++) {
                stringTaskBuffer.addData(new String(byteArrTaskBuffer.get(i)));
            }
            return stringTaskBuffer;
        }
    }

    private static class TransCompMediatorTask implements Runnable {
        private StringTaskBuffer taskBuffer;
        private FindResultListener findResultListener;
        private static Queue<Future<RecordLazyEvalTaskBuffer>> lazyEvalTaskQueue = new LinkedList<>();

        TransCompMediatorTask(StringTaskBuffer taskBuffer, FindResultListener findResultListener) {
            this.taskBuffer = taskBuffer;
            this.findResultListener = findResultListener;
        }

        static void syncConsumeReadyJobs(final FindResultListener findResultListener) {
            while (!lazyEvalTaskQueue.isEmpty()) {
                Future<RecordLazyEvalTaskBuffer> recordLazyEvalTaskBufferFuture = lazyEvalTaskQueue.poll();
                RecordLazyEvalTaskBuffer recordLazyEvalTaskBuffer = null;
                while (recordLazyEvalTaskBuffer == null) {
                    try {
                        recordLazyEvalTaskBuffer = recordLazyEvalTaskBufferFuture.get();
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                    }
                }
                computationPool.execute(new ComputationTask(recordLazyEvalTaskBuffer, findResultListener));
            }
        }

        @Override
        public void run() {
            try {
                while (lazyEvalTaskQueue.size() > 0) {
                    Future<RecordLazyEvalTaskBuffer> futureWork = lazyEvalTaskQueue.peek();
                    if (futureWork.isDone()) {
                        computationPool.execute(new ComputationTask(futureWork.get(), findResultListener));
                        lazyEvalTaskQueue.poll();
                    } else {
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            Future<RecordLazyEvalTaskBuffer> recordLazyEvalTaskBufferFuture = transformPool.submit(new TransformTask(taskBuffer, 0, taskBuffer.length()));
            lazyEvalTaskQueue.add(recordLazyEvalTaskBufferFuture);
        }
    }

    private static class TransformTask implements Callable<RecordLazyEvalTaskBuffer> {
        private StringTaskBuffer taskBuffer;
        private int startIdx; // inclusive
        private int endIdx;   // exclusive

        TransformTask(StringTaskBuffer taskBuffer, int startIdx, int endIdx) {
            this.taskBuffer = taskBuffer;
            this.startIdx = startIdx;
            this.endIdx = endIdx;
        }

        @Override
        public RecordLazyEvalTaskBuffer call() throws Exception {
            StringBuilder stringBuilder = new StringBuilder();
            RecordLazyEvalTaskBuffer recordLazyEvalTaskBuffer = new RecordLazyEvalTaskBuffer(endIdx - startIdx);
            for (int i = startIdx; i < endIdx; i++) {
                recordLazyEvalTaskBuffer.addData(new RecordLazyEval(taskBuffer.get(i), stringBuilder));
            }
            return recordLazyEvalTaskBuffer;
        }
    }

    public interface FindResultListener {
        void sendToClient(String result);
    }

    private static class ComputationTask implements Runnable {
        private RecordLazyEvalTaskBuffer recordLazyEvalTaskBuffer;
        private FindResultListener findResultListener;

        ComputationTask(RecordLazyEvalTaskBuffer recordLazyEvalTaskBuffer, FindResultListener findResultListener) {
            this.recordLazyEvalTaskBuffer = recordLazyEvalTaskBuffer;
            this.findResultListener = findResultListener;
        }

        @Override
        public void run() {
            for (int j = 0; j < recordLazyEvalTaskBuffer.length(); j++) {
                String result = sequentialRestore.compute(recordLazyEvalTaskBuffer.get(j));
                if (result != null) {
                    findResultListener.sendToClient(result);
                }
            }
        }
    }

    public static void OneRoundComputation(String fileName, final FindResultListener findResultListener) throws IOException {
        long startTime = System.currentTimeMillis();

        ReversedLinesDirectReader reversedLinesFileReader = new ReversedLinesDirectReader(fileName);
        byte[] line;
        long lineCount = 0;

        ByteArrTaskBuffer byteArrTaskBuffer = new ByteArrTaskBuffer();
        while ((line = reversedLinesFileReader.readLineBytes()) != null) {
            if (byteArrTaskBuffer.isFull()) {
                decodeDispatchMediatorPool.execute(new DecodeDispatchTask(byteArrTaskBuffer, findResultListener));
                byteArrTaskBuffer = new ByteArrTaskBuffer();
            }
            byteArrTaskBuffer.addData(line);
            lineCount += line.length;
        }
        decodeDispatchMediatorPool.execute(new DecodeDispatchTask(byteArrTaskBuffer, findResultListener));
        decodeDispatchMediatorPool.execute(new Runnable() {
            @Override
            public void run() {
                DecodeDispatchTask.syncConsumeReadyJobs(findResultListener);
            }
        });

        long endTime = System.currentTimeMillis();
        System.out.println("computation time:" + (endTime - startTime));
        if (Server.logger != null) {
            Server.logger.info("computation time:" + (endTime - startTime));
            Server.logger.info("Byte count: " + lineCount);
        }
    }

    public static void JoinComputationThread() {
        // join page cache
        pageCachePool.shutdown();
        try {
            pageCachePool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // join decode pool
        decodePool.shutdown();
        try {
            decodePool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // join decode dispatch
        decodeDispatchMediatorPool.shutdown();
        try {
            decodeDispatchMediatorPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // join transform states
        transformPool.shutdown();
        try {
            transformPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // join mediator
        transCompMediatorPool.shutdown();
        try {
            transCompMediatorPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // join computation
        computationPool.shutdown();
        try {
            computationPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}

