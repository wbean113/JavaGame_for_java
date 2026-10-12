package game.net;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WireTest {
    @Test void utf8LimitCountsBytesAndAcceptsAnExactLengthMessage() throws Exception {
        assertEquals("中文", Wire.read(input("中文\n"), 6));
        assertThrows(IOException.class, () -> Wire.read(input("中文\n"), 5));
        assertEquals("", Wire.read(input("\n"), 0));
    }

    @Test void emptyStreamIsEofButPartialMessageIsRejected() throws Exception {
        assertNull(Wire.read(input(""), 100));
        assertThrows(IOException.class, () -> Wire.read(input("{\"type\":\"ping\"}"), 100));
    }

    @Test void coalescedAndByteFragmentedMessagesPreserveBoundaries() throws Exception {
        var incoming = input("one\ntwo\n");
        assertEquals("one", Wire.read(incoming, 3));
        assertEquals("two", Wire.read(incoming, 3));
        assertNull(Wire.read(incoming, 3));
    }

    @Test void writeProducesOneCompleteUtf8JsonLine() throws Exception {
        var outgoing = new ByteArrayOutputStream();
        Wire.write(outgoing, Map.of("type", "测试", "name", "甲\n乙"));
        byte[] bytes = outgoing.toByteArray();
        assertEquals('\n', bytes[bytes.length - 1]);
        var decoded = Json.GSON.fromJson(Wire.read(new ByteArrayInputStream(bytes), 1000), Map.class);
        assertEquals("测试", decoded.get("type"));
        assertEquals("甲\n乙", decoded.get("name"));
    }

    private static InputStream input(String s) { return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)); }
}
