package com.alibaba.middleware.race.sync.server2;

/**
 * Created by yche on 6/18/17.
 * use builder pattern
 */
public class KeyOperation {
    private byte operationType;
    private Long prevKey;
    private Long curKey;

    public KeyOperation(byte operationType) {
        this.operationType = operationType;
        this.prevKey = null;
        this.curKey = null;
    }

    KeyOperation preKey(long prevKey) {
        this.prevKey = prevKey;
        return this;
    }

    KeyOperation curKey(long curKey) {
        this.curKey = curKey;
        return this;
    }

    byte getOperationType() {
        return operationType;
    }

    Long getPrevKey() {
        return prevKey;
    }

    Long getCurKey() {
        return curKey;
    }

    boolean isKeyChanged() {
        return !getPrevKey().equals(getCurKey());
    }

    private static long pkLowerBound;
    private static long pkUpperBound;

    public static void initRange(long lowerBound, long upperBound) {
        pkLowerBound = lowerBound;
        pkUpperBound = upperBound;
    }

    static boolean isKeyInRange(long key) {
        return pkLowerBound < key && key < pkUpperBound;
    }

}
