package game.net;

import com.google.gson.*;
import game.core.CardDefinition;
import game.core.Cards;
import game.server.GameServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real loopback TCP integration; this does not claim testing on two physical LAN machines. */
@Timeout(30)
class NetworkIntegrationTest {
    private GameServer server;
    private final List<Client> clients = new ArrayList<>();

    @BeforeEach void startServer() throws IOException {
        server = new GameServer("127.0.0.1", 0);
        server.start();
    }

    @AfterEach void closeAll() throws IOException {
        for (var client : clients) client.close();
        server.close();
    }

    private Client connect() throws IOException {
        var client = new Client(server.port());
        clients.add(client);
        assertEquals(1, client.receive("hello").get("protocol").getAsInt());
        return client;
    }

    private Match openMatch(String rule, String mode, int first) throws IOException {
        var a = connect();
        var b = connect();
        a.send(Map.of("type", "create", "name", "甲方测试", "rule", rule, "mode", mode, "first", first));
        String room = a.receive("state").get("room").getAsString();
        b.send(Map.of("type", "join", "name", "乙方测试", "room", room));
        a.receive("state"); b.receive("state");
        a.send(Map.of("type", "ready"));
        a.receive("state"); b.receive("state");
        b.send(Map.of("type", "ready"));
        var match = new Match(a, b, room);
        match.readStates();
        assertEquals(first, match.view(0).get("active").getAsInt());
        assertFalse(match.view(0).get("over").getAsBoolean());
        return match;
    }

    @Test void matchmakingRequiresTwoReadyPlayersAndRejectsThirdSeat() throws Exception {
        var a = connect();
        a.send(Map.of("type", "ready"));
        assertTrue(a.receive("error").get("message").getAsString().contains("房间"));
        a.send(Map.of("type", "create", "name", "先手", "rule", "F", "mode", "base", "first", 0));
        var solo = a.receive("state");
        String code = solo.get("room").getAsString();
        assertEquals(6, code.length());
        assertFalse(solo.has("view"));
        a.send(Map.of("type", "ready"));
        assertFalse(a.receive("state").has("view"));
        var b = connect();
        b.send(Map.of("type", "join", "name", "后手", "room", code));
        a.receive("state");
        var joined = b.receive("state");
        assertFalse(joined.has("view"));
        assertFalse(joined.getAsJsonArray("ready").get(0).getAsBoolean(), "Joining resets stale readiness");
        var c = connect();
        c.send(Map.of("type", "join", "name", "第三人", "room", code));
        assertTrue(c.receive("error").get("message").getAsString().contains("两位"));
        b.send(Map.of("type", "ready"));
        assertFalse(a.receive("state").has("view")); b.receive("state");
        a.send(Map.of("type", "ready"));
        assertTrue(a.receive("state").has("view"));
        assertTrue(b.receive("state").has("view"));
    }

    @Test void projectionsAndInProgressExportHideOpponentsCardsAndSeed() throws Exception {
        var match = openMatch("G", "spells", 1);
        for (int f = 0; f < 2; f++) {
            var view = match.view(f);
            var own = view.getAsJsonArray("players").get(f).getAsJsonObject();
            var other = view.getAsJsonArray("players").get(1 - f).getAsJsonObject();
            assertEquals(f == 1 ? 4 : 3, own.getAsJsonArray("hand").size());
            assertEquals(own.get("handCount").getAsInt(), own.getAsJsonArray("hand").size());
            assertEquals(0, other.getAsJsonArray("hand").size());
            assertEquals(f == 1 ? 3 : 4, other.get("handCount").getAsInt());
            assertNoSecrets(match.states[f]);
            match.clients[f].send(Map.of("type", "export", "notes", "进行中导出\n中文"));
            var record = match.clients[f].receive("export").getAsJsonObject("record");
            assertFalse(record.get("complete").getAsBoolean());
            assertEquals("进行中导出\n中文", record.get("notes").getAsString());
            assertEquals(view, record.getAsJsonObject("view"));
            assertNoSecrets(record);
        }
    }

    @Test void endPreviewHasNoSideEffectsAndPreservesRequestIdentity() throws Exception {
        var match = openMatch("F", "base", 0);
        JsonObject before = match.view(0).deepCopy();
        for (int i = 0; i < 3; i++) {
            match.clients[0].send(Map.of("type", "preview", "action", "end", "version", match.version(), "requestId", 100 + i));
            var response = match.clients[0].receive("preview");
            assertEquals(100 + i, response.get("requestId").getAsInt());
            assertEquals(match.version(), response.get("version").getAsLong());
            assertEquals(0, response.getAsJsonObject("preview").get("balance").getAsInt());
            assertNoSecrets(response);
        }
        match.clients[0].send(Map.of("type", "export"));
        assertEquals(before, match.clients[0].receive("export").getAsJsonObject("record").getAsJsonObject("view"));
        match.command(0, Map.of("type", "end", "version", match.version()));
        assertEquals(before.get("version").getAsLong() + 1, match.version());
        assertEquals(1, match.view(0).get("active").getAsInt());
    }

    @Test void invalidDuplicateAndStaleActionsAreRejectedWithoutMutation() throws Exception {
        var match = openMatch("G", "spells", 0);
        long original = match.version();
        JsonObject before = match.view(0).deepCopy();
        reject(match.clients[1], Map.of("type", "end", "version", original), "回合");
        reject(match.clients[0], Map.of("type", "play", "version", original, "instanceId", Long.MAX_VALUE, "lane", 0), "手牌");
        reject(match.clients[0], Map.of("type", "end", "version", original + 1), "状态");
        reject(match.clients[0], Map.of("type", "end", "version", 0.5), "整数");
        reject(match.clients[0], Map.of("type", "play", "version", original, "instanceId", 1, "lane", 2147483648L), "越界");
        reject(match.clients[0], Map.of("type", "ready"), "进行中");
        match.clients[0].send(Map.of("type", "export"));
        assertEquals(before, match.clients[0].receive("export").getAsJsonObject("record").getAsJsonObject("view"));
        match.command(0, Map.of("type", "end", "version", original));
        reject(match.clients[0], Map.of("type", "end", "version", original), "回合");
        match.command(1, Map.of("type", "end", "version", match.version()));
        long current = match.version();
        reject(match.clients[0], Map.of("type", "end", "version", original), "状态");
        match.clients[0].send(Map.of("type", "export"));
        assertEquals(current, match.clients[0].receive("export").getAsJsonObject("record").getAsJsonObject("view").get("version").getAsLong());
    }

    @Test void playPreviewDoesNotSpendManaRemoveCardOrOccupySlot() throws Exception {
        var match = openMatch("G", "base", 0);
        Map<String, Object> play = null;
        for (int i = 0; i < 20 && play == null; i++) {
            int active = match.view(0).get("active").getAsInt();
            play = choosePlay(match.view(active));
            if (play == null) match.command(active, Map.of("type", "end", "version", match.version()));
        }
        assertNotNull(play);
        int active = match.view(0).get("active").getAsInt();
        var before = match.view(active).deepCopy();
        var preview = new HashMap<String, Object>(play);
        preview.put("type", "preview"); preview.put("action", "play"); preview.put("requestId", 2026);
        match.clients[active].send(preview);
        var predicted = match.clients[active].receive("preview").getAsJsonObject("preview");
        assertNoSecrets(predicted);
        match.clients[active].send(Map.of("type", "export"));
        assertEquals(before, match.clients[active].receive("export").getAsJsonObject("record").getAsJsonObject("view"));
        match.command(active, play);
        assertEquals(before.get("version").getAsLong() + 1, match.version());
        match.command(active, Map.of("type", "end", "version", match.version()));
        assertEquals(predicted.get("balance"), match.view(active).get("balance"));
        assertEquals(predicted.get("over"), match.view(active).get("over"));
    }

    @Test void previewCannotRevealOpponentsNextCardThroughHandLimitBurn() throws Exception {
        var match = openMatch("F", "base", 0);
        for (int i = 0; i < 20; i++) {
            int active = match.view(0).get("active").getAsInt();
            var other = match.view(active).getAsJsonArray("players").get(1 - active).getAsJsonObject();
            if (other.get("handCount").getAsInt() == 10) break;
            match.command(active, Map.of("type", "end", "version", match.version()));
        }
        int active = match.view(0).get("active").getAsInt();
        var other = match.view(active).getAsJsonArray("players").get(1 - active).getAsJsonObject();
        assertEquals(10, other.get("handCount").getAsInt());
        assertTrue(other.get("deckCount").getAsInt() > 0);
        match.clients[active].send(Map.of("type", "preview", "action", "end", "version", match.version(), "requestId", 99));
        var preview = match.clients[active].receive("preview").getAsJsonObject("preview");
        for (var event : preview.getAsJsonArray("events")) {
            assertFalse(Set.of("draw", "burn", "turn_start", "resource_grant", "empty_deck")
                    .contains(event.getAsJsonObject().get("type").getAsString()), "Preview leaked next-turn information: " + event);
        }
    }

    @ParameterizedTest @CsvSource({"F,base,0", "F,spells,1", "G,base,1", "G,spells,0"})
    void twoTcpPlayersCanPlayACompleteMatch(String rule, String mode, int first) throws Exception {
        var match = openMatch(rule, mode, first);
        int actions = 0, plays = 0;
        while (!match.view(0).get("over").getAsBoolean() && actions < 300) {
            int active = match.view(0).get("active").getAsInt();
            Map<String, Object> play = choosePlay(match.view(active));
            if (play == null) match.command(active, Map.of("type", "end", "version", match.version()));
            else { match.command(active, play); plays++; }
            actions++;
            assertEquals(match.view(0).get("version"), match.view(1).get("version"));
            assertEquals(match.view(0).get("balance"), match.view(1).get("balance"));
            assertNoSecrets(match.states[0]); assertNoSecrets(match.states[1]);
            for (int f = 0; f < 2; f++) assertEquals(0, match.view(f).getAsJsonArray("players")
                    .get(1 - f).getAsJsonObject().getAsJsonArray("hand").size());
            // Keep each peer comfortably below the production anti-flood threshold.
            Thread.sleep(30);
        }
        assertTrue(match.view(0).get("over").getAsBoolean(), "The game must terminate");
        assertTrue(plays >= 2, "This scenario exercises actual card play over TCP");
        match.clients[0].send(Map.of("type", "export", "notes", "真实TCP集成测试"));
        var record = match.clients[0].receive("export").getAsJsonObject("record");
        var game = record.getAsJsonObject("game");
        assertTrue(game.get("over").getAsBoolean());
        assertFalse(game.get("seed").getAsString().isBlank());
        assertEquals(2, game.getAsJsonArray("initialDecks").size());
        assertEquals(actions, game.getAsJsonArray("actions").size());
        assertEquals(match.view(0).get("balance"), game.get("balance"));
    }

    @Test void thirtyTurnsEndInDrawAndBothPlayersCanStartFreshMatch() throws Exception {
        var match = openMatch("F", "base", 0);
        for (int i = 0; i < 60; i++) {
            match.command(i % 2, Map.of("type", "end", "version", match.version()));
            Thread.sleep(25);
        }
        assertTrue(match.view(0).get("over").getAsBoolean());
        assertTrue(!match.view(0).has("winner") || match.view(0).get("winner").isJsonNull());
        assertEquals("双方各完成30回合", match.view(0).get("reason").getAsString());
        for (var p : match.view(0).getAsJsonArray("players")) assertEquals(30, p.getAsJsonObject().get("completed").getAsInt());
        long previousFinalVersion = match.version();
        match.clients[0].send(Map.of("type", "ready")); match.readStates();
        assertTrue(match.view(0).get("over").getAsBoolean());
        match.clients[1].send(Map.of("type", "ready")); match.readStates();
        assertFalse(match.view(0).get("over").getAsBoolean());
        assertTrue(match.version() > previousFinalVersion, "Room versions remain monotonic across rematches");
        assertEquals(4, match.view(0).getAsJsonArray("players").get(0).getAsJsonObject().getAsJsonArray("hand").size());
        assertEquals(0, match.view(0).get("balance").getAsInt());
    }

    @Test void delayedPreviousMatchActionIsRejectedAfterRematchWithoutChangingState() throws Exception {
        var match = openMatch("G", "base", 0);
        assertEquals(0, match.version(), "The first game in a new room starts at version zero");
        var delayedOldAction = Map.of("type", "end", "version", match.version());
        for (int i = 0; i < 60; i++) {
            match.command(i % 2, Map.of("type", "end", "version", match.version()));
            Thread.sleep(25);
        }
        long previousFinalVersion = match.version();
        assertTrue(match.view(0).get("over").getAsBoolean());
        match.clients[0].send(Map.of("type", "ready")); match.readStates();
        match.clients[1].send(Map.of("type", "ready")); match.readStates();
        assertTrue(match.version() > previousFinalVersion);
        assertEquals(0, match.view(0).get("active").getAsInt());
        var before = match.view(0).deepCopy();

        // This identical command was legal at the opening of the previous game.
        reject(match.clients[0], delayedOldAction, "状态");
        reject(match.clients[0], Map.of("type", "preview", "action", "end", "version", 0L, "requestId", 88), "状态");
        match.clients[0].send(Map.of("type", "export"));
        assertEquals(before, match.clients[0].receive("export").getAsJsonObject("record").getAsJsonObject("view"));

        match.command(0, Map.of("type", "end", "version", match.version()));
        assertEquals(before.get("version").getAsLong() + 1, match.version());
        assertEquals(1, match.view(0).get("active").getAsInt());
    }

    @Test void disconnectFinishesGameAndNewSessionCannotInheritHiddenCards() throws Exception {
        var match = openMatch("G", "base", 0);
        match.clients[1].close();
        var ended = match.clients[0].receive("state");
        assertTrue(ended.getAsJsonObject("view").get("over").getAsBoolean());
        assertEquals(0, ended.getAsJsonObject("view").get("winner").getAsInt());
        assertFalse(ended.getAsJsonArray("connected").get(1).getAsBoolean());
        var replacement = connect();
        replacement.send(Map.of("type", "join", "name", "新会话", "room", match.room));
        assertFalse(match.clients[0].receive("state").has("view"));
        assertFalse(replacement.receive("state").has("view"));
        reject(replacement, Map.of("type", "export"), "准备");
    }

    @Test void malformedMessagesReturnErrorsAndConnectionStillWorks() throws Exception {
        var client = connect();
        for (String bad : List.of("{broken", "[]", "null", "{\"type\":7}", "{\"type\":\"join\",\"room\":\"000000\"}")) {
            client.raw(bad + "\n");
            assertFalse(client.receive("error").get("message").getAsString().isBlank());
        }
        client.send(Map.of("type", "ping"));
        client.receive("pong");
    }

    @Test void oversizedAndUnterminatedInputCloseOnlyTheOffendingSession() throws Exception {
        var large = connect();
        large.raw("x".repeat(16_385) + "\n");
        assertDisconnected(large);
        var partial = connect();
        partial.raw("{\"type\":\"ping\"}");
        partial.socket.shutdownOutput();
        assertDisconnected(partial);
        var healthy = connect();
        healthy.send(Map.of("type", "ping")); healthy.receive("pong");
    }

    @Test void gameConnectionDeliversMessagesAsynchronouslyAndReportsServerClosure() throws Exception {
        var received = new LinkedBlockingQueue<JsonObject>();
        var failures = new LinkedBlockingQueue<String>();
        try (var connection = new GameConnection("127.0.0.1", server.port(), received::add, failures::add)) {
            assertEquals("hello", Objects.requireNonNull(received.poll(3, TimeUnit.SECONDS)).get("type").getAsString());
            connection.send(Map.of("type", "ping"));
            assertEquals("pong", Objects.requireNonNull(received.poll(3, TimeUnit.SECONDS)).get("type").getAsString());
            server.close();
            assertNotNull(failures.poll(3, TimeUnit.SECONDS));
        }
    }

    private static void reject(Client client, Map<String, ?> command, String expected) throws IOException {
        client.send(command);
        assertTrue(client.receive("error").get("message").getAsString().contains(expected));
    }

    private static void assertDisconnected(Client client) throws IOException {
        try { assertNull(Wire.read(client.input, 2_000_000)); }
        catch (SocketException expected) { /* Reset and graceful EOF both constitute disconnect. */ }
    }

    private static void assertNoSecrets(JsonElement element) {
        if (element.isJsonObject()) for (var entry : element.getAsJsonObject().entrySet()) {
            assertFalse(Set.of("seed", "initialDecks", "actions", "deck", "nextId").contains(entry.getKey()), "Leaked internal field " + entry.getKey());
            assertNoSecrets(entry.getValue());
        }
        else if (element.isJsonArray()) for (var item : element.getAsJsonArray()) assertNoSecrets(item);
    }

    private static Map<String, Object> choosePlay(JsonObject view) {
        int active = view.get("active").getAsInt();
        var players = view.getAsJsonArray("players");
        var own = players.get(active).getAsJsonObject();
        var enemy = players.get(1 - active).getAsJsonObject();
        var board = own.getAsJsonArray("board");
        var enemies = enemy.getAsJsonArray("board");
        int mana = own.get("mana").getAsInt();
        for (var raw : own.getAsJsonArray("hand")) {
            var card = raw.getAsJsonObject();
            var definition = Cards.get(card.get("id").getAsString());
            if (definition.cost() > mana) continue;
            for (int lane = 0; lane < 3; lane++) {
                if (definition.type() == CardDefinition.Type.SPELL ? enemies.get(lane).isJsonNull() : !board.get(lane).isJsonNull()) continue;
                int sacrifice = -1;
                if (definition.id().equals("grief")) {
                    for (int i = 0; i < 3; i++) if (!board.get(i).isJsonNull()) { sacrifice = i; break; }
                    if (sacrifice < 0) continue;
                }
                return Map.of("type", "play", "version", view.get("version").getAsLong(), "instanceId", card.get("instanceId").getAsLong(), "lane", lane, "sacrifice", sacrifice);
            }
        }
        return null;
    }

    private static final class Match {
        final Client[] clients;
        final JsonObject[] states = new JsonObject[2];
        final String room;
        Match(Client a, Client b, String room) { clients = new Client[]{a, b}; this.room = room; }
        JsonObject view(int f) { return states[f].getAsJsonObject("view"); }
        long version() { return view(0).get("version").getAsLong(); }
        void readStates() throws IOException { for (int f = 0; f < 2; f++) states[f] = clients[f].receive("state"); }
        void command(int faction, Map<String, ?> message) throws IOException { clients[faction].send(message); readStates(); }
    }

    private static final class Client implements AutoCloseable {
        final Socket socket;
        final InputStream input;
        Client(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(4000); socket.setTcpNoDelay(true);
            input = new BufferedInputStream(socket.getInputStream());
        }
        void send(Object value) throws IOException { Wire.write(socket.getOutputStream(), value); }
        void raw(String text) throws IOException { socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush(); }
        JsonObject receive(String type) throws IOException {
            String line = Wire.read(input, 2_000_000);
            assertNotNull(line, "Unexpected disconnect waiting for " + type);
            var result = JsonParser.parseString(line).getAsJsonObject();
            assertEquals(type, result.get("type").getAsString(), () -> "Unexpected response: " + result);
            return result;
        }
        @Override public void close() throws IOException { socket.close(); }
    }
}
