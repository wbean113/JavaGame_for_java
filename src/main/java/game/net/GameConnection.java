package game.net;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 连接、收发、心跳均在后台线程，UI只收到消息回调。 */
public final class GameConnection implements AutoCloseable {
    private final Socket socket=new Socket();
    private final BlockingQueue<Object> outgoing=new ArrayBlockingQueue<>(64);
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private volatile boolean closed;
    public GameConnection(String host,int port,Consumer<JsonObject> receiver,Consumer<String> failure) throws IOException {
        socket.connect(new InetSocketAddress(host,port),5000); socket.setTcpNoDelay(true); socket.setSoTimeout(120_000);
        Thread.ofVirtual().start(()->{
            try { while(!closed) Wire.write(socket.getOutputStream(),outgoing.take()); }
            catch(Exception e) { if(!closed) failure.accept("发送失败："+e.getMessage()); close(); }
        });
        Thread.ofVirtual().start(()->{
            try {
                var input=new BufferedInputStream(socket.getInputStream());
                for(String line;!closed&&(line=Wire.read(input,2_000_000))!=null;) receiver.accept(JsonParser.parseString(line).getAsJsonObject());
                if(!closed) failure.accept("与服务器断开；本局不支持断线续局");
            } catch(Exception e) { if(!closed) failure.accept("连接中断："+e.getMessage()); }
            finally { close(); }
        });
        heartbeat.scheduleAtFixedRate(()->send(Map.of("type","ping")),20,20,TimeUnit.SECONDS);
    }
    public void send(Object value) { if(closed) throw new IllegalStateException("连接已关闭"); if(!outgoing.offer(value)) { close(); throw new IllegalStateException("发送队列已满"); } }
    @Override public void close() { closed=true; heartbeat.shutdownNow(); outgoing.offer(Map.of("type","closed")); try{socket.close();}catch(IOException ignored){} }
}
