package game.server;

import com.google.gson.*;
import game.core.*;
import game.net.*;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 每个房间的操作在同一个锁内串行；慢客户端的写入由独立有界队列完成。 */
public final class GameServer implements AutoCloseable {
    private final ServerSocket listener;
    private final Map<String,Room> rooms=new ConcurrentHashMap<>();
    private final Set<Peer> peers=ConcurrentHashMap.newKeySet();
    private final Semaphore slots=new Semaphore(128);
    private volatile boolean closed;
    private final SecureRandom random=new SecureRandom();
    private static final class Room {
        final String code, rule, mode;
        final int first;
        final Peer[] seats=new Peer[2];
        final boolean[] ready=new boolean[2];
        GameEngine engine;
        long nextVersion;
        Room(String code,String rule,String mode,int first) { this.code=code;this.rule=rule;this.mode=mode;this.first=first; }
    }
    public GameServer(int port) throws IOException { this("0.0.0.0",port); }
    public GameServer(String bind,int port) throws IOException {
        listener=new ServerSocket(); listener.bind(new InetSocketAddress(bind,port));
    }
    public int port() { return listener.getLocalPort(); }
    public void start() { Thread.ofVirtual().name("accept").start(()->{
        while(!closed) try {
            Socket socket=listener.accept();
            if(!slots.tryAcquire()) { socket.close(); continue; }
            socket.setTcpNoDelay(true); socket.setSoTimeout(120_000);
            var peer=new Peer(socket); peers.add(peer); peer.start();
        } catch(IOException e) { if(!closed) System.err.println("接入失败："+e.getMessage()); }
    }); }
    private final class Peer {
        final Socket socket;
        final BlockingQueue<Object> output=new ArrayBlockingQueue<>(64);
        volatile boolean gone;
        final AtomicBoolean disconnecting=new AtomicBoolean();
        Room room; int faction=-1; String name;
        long window=System.nanoTime(); int requests;
        Peer(Socket socket) { this.socket=socket; }
        void start() {
            Thread.ofVirtual().start(()->{
                try { while(!gone) Wire.write(socket.getOutputStream(),output.take()); }
                catch(Exception e) { disconnect(); }
            });
            Thread.ofVirtual().start(()->{
                try {
                    var input=new BufferedInputStream(socket.getInputStream());
                    for(String line;!gone&&(line=Wire.read(input,16_384))!=null;) {
                        long now=System.nanoTime(); if(now-window>1_000_000_000L) { requests=0; window=now; }
                        if(++requests>40) throw new IOException("请求过于频繁");
                        try {
                            var parsed=JsonParser.parseString(line);
                            if(!parsed.isJsonObject()) throw new IllegalArgumentException("需要JSON对象");
                            handle(this,parsed.getAsJsonObject());
                        } catch(RuntimeException e) { send(Map.of("type","error","message",e instanceof IllegalArgumentException?String.valueOf(e.getMessage()):"消息格式无效")); }
                    }
                } catch(IOException e) { /* 超时、超长、断线统一结束会话 */ }
                finally { disconnect(); }
            });
            send(Map.of("type","hello","protocol",1));
        }
        void send(Object value) { if(!gone&&!output.offer(value)) disconnect(); }
        void disconnect() {
            if(!disconnecting.compareAndSet(false,true)) return; gone=true;
            try { socket.close(); } catch(IOException ignored) {}
            // 唤醒等待队列的 writer，关闭的 socket 会使其退出。
            output.offer(Map.of("type","closed")); peers.remove(this); slots.release();
            Room r=room;
            if(r!=null) synchronized(r) {
                if(r.seats[faction]!=this) return;
                r.seats[faction]=null; Arrays.fill(r.ready,false);
                if(r.engine!=null) r.engine.disconnect(faction);
                broadcast(r);
                if(r.seats[0]==null&&r.seats[1]==null) rooms.remove(r.code,r);
            }
        }
    }
    private void handle(Peer peer,JsonObject request) {
        String type=str(request,"type",20);
        if(type.equals("ping")) { peer.send(Map.of("type","pong")); return; }
        if(type.equals("create")||type.equals("join")) {
            if(peer.room!=null) throw new IllegalArgumentException("已在房间中，请断开后再创建或加入");
            String name=str(request,"name",32);
            Room room;
            if(type.equals("create")) {
                String rule=str(request,"rule",1), mode=str(request,"mode",10); int first=integer(request,"first");
                if(!Set.of("F","G").contains(rule)||!Set.of("base","spells").contains(mode)||first<0||first>1) throw new IllegalArgumentException("开局配置无效");
                synchronized(rooms) {
                    if(rooms.size()>=64) throw new IllegalArgumentException("房间已满");
                    String code; do { code="%06d".formatted(random.nextInt(1_000_000)); } while(rooms.containsKey(code));
                    room=new Room(code,rule,mode,first); rooms.put(code,room);
                }
            } else {
                room=rooms.get(str(request,"room",6)); if(room==null) throw new IllegalArgumentException("房间不存在");
            }
            synchronized(room) {
                int f=room.seats[0]==null?0:room.seats[1]==null?1:-1;
                if(f<0) throw new IllegalArgumentException("房间已有两位玩家");
                peer.name=name; peer.room=room; peer.faction=f; room.seats[f]=peer;
                // 新会话不能继承之前的隐藏手牌或正在进行的局。
                room.engine=null; Arrays.fill(room.ready,false); broadcast(room);
            }
            return;
        }
        Room room=peer.room; if(room==null) throw new IllegalArgumentException("请先创建或加入房间");
        synchronized(room) {
            if(type.equals("ready")) {
                if(room.engine!=null&&!room.engine.snapshot().over) throw new IllegalArgumentException("对局进行中");
                room.ready[peer.faction]=true;
                if(room.seats[0]!=null&&room.seats[1]!=null&&room.ready[0]&&room.ready[1]) {
                    // 种子只在服务端产生；即使客户端知道算法，也无法推算对方牌序。
                    room.engine=new GameEngine(room.rule,room.mode,UUID.randomUUID().toString(),room.first,
                            new String[]{room.seats[0].name,room.seats[1].name},null,room.nextVersion); Arrays.fill(room.ready,false);
                }
                broadcast(room); return;
            }
            if(room.engine==null) throw new IllegalArgumentException("双方准备后才能开始");
            if(type.equals("export")) {
                String notes=request.has("notes")?strAllowEmpty(request,"notes",2000):"";
                var s=room.engine.snapshot();
                peer.send(Map.of("type","export","record",s.over?Records.full(s,notes):Records.visible(GameView.of(s,peer.faction),notes))); return;
            }
            long version=longInteger(request,"version");
            String action=type.equals("preview")?str(request,"action",10):type;
            var command=new MatchState.Action(action,peer.faction,
                    action.equals("play")?longInteger(request,"instanceId"):0,
                    action.equals("play")?integer(request,"lane"):-1,
                    request.has("sacrifice")?integer(request,"sacrifice"):-1,version,
                    request.has("blocked")&&request.get("blocked").getAsBoolean());
            if(type.equals("preview")) peer.send(Map.of("type","preview","version",version,"requestId",longInteger(request,"requestId"),"preview",room.engine.preview(command)));
            else { room.engine.apply(command); broadcast(room); }
        }
    }
    private void broadcast(Room room) {
        if(room.engine!=null) room.nextVersion=Math.max(room.nextVersion,room.engine.snapshot().version+1);
        for(int f=0;f<2;f++) {
            var peer=room.seats[f]; if(peer==null) continue;
            var message=new LinkedHashMap<String,Object>(); message.put("type","state"); message.put("room",room.code);
            message.put("faction",f); message.put("rule",room.rule); message.put("mode",room.mode); message.put("first",room.first);
            message.put("names",new String[]{room.seats[0]==null?"等待加入":room.seats[0].name,room.seats[1]==null?"等待加入":room.seats[1].name});
            message.put("connected",new boolean[]{room.seats[0]!=null,room.seats[1]!=null}); message.put("ready",room.ready.clone());
            if(room.engine!=null) message.put("view",GameView.of(room.engine.snapshot(),f));
            peer.send(message);
        }
    }
    private static String str(JsonObject o,String key,int max) {
        String s=strAllowEmpty(o,key,max); if(s.isBlank()) throw new IllegalArgumentException(key+"不能为空"); return s;
    }
    private static String strAllowEmpty(JsonObject o,String key,int max) {
        if(!o.has(key)||!o.get(key).isJsonPrimitive()||!o.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("缺少文本字段："+key);
        String s=o.get(key).getAsString(); if(s.length()>max||s.chars().anyMatch(c->c<32&&c!='\n'&&c!='\t')) throw new IllegalArgumentException(key+"过长或有控制字符"); return s;
    }
    private static long longInteger(JsonObject o,String key) {
        if(!o.has(key)||!o.get(key).isJsonPrimitive()||!o.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException("缺少整数："+key);
        try { return o.get(key).getAsBigDecimal().longValueExact(); }
        catch(ArithmeticException e) { throw new IllegalArgumentException(key+"必须为整数"); }
    }
    private static int integer(JsonObject o,String key) { long n=longInteger(o,key); if(n<Integer.MIN_VALUE||n>Integer.MAX_VALUE) throw new IllegalArgumentException(key+"越界"); return (int)n; }
    @Override public void close() throws IOException { closed=true; listener.close(); for(var p:List.copyOf(peers)) p.disconnect(); }
    public static void main(String[] args) throws Exception {
        int port=args.length>0?Integer.parseInt(args[0]):23456;
        var server=new GameServer(port); server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{try{server.close();}catch(IOException ignored){}}));
        System.out.println("心理天平服务器已启动，TCP端口 "+server.port()+"。局域网客户端连接本机IPv4；Ctrl+C关闭。");
        new CountDownLatch(1).await();
    }
}
