import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by wanshao on 2017/5/25.
 */
public class Client {

    //idle时间
    private final static int readerIdleTimeSeconds = 40;
    private final static int writerIdleTimeSeconds = 50;
    private final static int allIdleTimeSeconds = 100;

    public static void main(String[] args) throws Exception {
        initProperties();
        Logger logger = LoggerFactory.getLogger(Client.class);
        logger.info("Welcome");
        Client client = new Client();
        client.connect("127.0.0.1",5527);
    }


    /**
     * 初始化系统属性
     */
    private static void initProperties(){
        System.setProperty("middleware.test.home",Constants.TESTER_HOME);
        System.setProperty("middleware.teamcode", Constants.TEAMCODE);
        System.setProperty("app.logging.level", Constants.LOG_LEVEL);
    }

    /**
     * 连接服务端
     * @param host
     * @param port
     * @throws Exception
     */
    public void connect(String host, int port) throws Exception {
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            Bootstrap b = new Bootstrap();
            b.group(workerGroup);
            b.channel(NioSocketChannel.class);
            b.option(ChannelOption.SO_KEEPALIVE, true);
            b.handler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline().addLast(new IdleStateHandler(10, 0, 0));
                    ch.pipeline().addLast(new ClientIdleEventHandler());
                    ch.pipeline().addLast(new ClientDemoInHandler());
                }
            });

            // Start the client.
            ChannelFuture f = b.connect(host, port).sync();

            // Wait until the connection is closed.
            f.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
        }

    }
}
