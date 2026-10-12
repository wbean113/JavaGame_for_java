package game.net;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** UTF-8 逐行 JSON；逐字节限制长度，避免 readLine 在检查之前无限分配内存。 */
public final class Wire {
    private Wire() {}
    public static String read(InputStream in,int maxBytes) throws IOException {
        var bytes=new ByteArrayOutputStream();
        for(int b;(b=in.read())!=-1;) {
            if(b=='\n') return bytes.toString(StandardCharsets.UTF_8);
            if(bytes.size()>=maxBytes) throw new IOException("消息超过长度限制");
            bytes.write(b);
        }
        if(bytes.size()!=0) throw new IOException("消息未完整结束");
        return null;
    }
    public static void write(OutputStream out,Object value) throws IOException {
        out.write((Json.GSON.toJson(value)+"\n").getBytes(StandardCharsets.UTF_8)); out.flush();
    }
}
