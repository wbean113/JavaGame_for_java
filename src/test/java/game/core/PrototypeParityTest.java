package game.core;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

/** 夹具由原版 JS 独立生成；逐次合法命令比较牌序、场面、费用、待用和累计统计。 */
class PrototypeParityTest {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    @TestFactory
    List<DynamicTest> compareEveryActionWithIndependentPrototype() throws IOException {
        JsonObject root;
        try (var input = new GZIPInputStream(Objects.requireNonNull(getClass().getResourceAsStream("/prototype-fixtures.json.gz")));
             var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        var fields = root.getAsJsonArray("playerFields");
        var tests = new ArrayList<DynamicTest>();
        for (var value : root.getAsJsonArray("fixtures")) {
            var fixture = value.getAsJsonObject();
            String title = fixture.get("source").getAsString() + " / " + fixture.get("rule").getAsString()
                    + " / " + fixture.get("mode").getAsString() + " / seed=" + fixture.get("seed").getAsString();
            tests.add(DynamicTest.dynamicTest(title, () -> replay(fixture, fields)));
        }
        assertEquals(37, tests.size(), "32局多种子对照 + 5份F真人记录");
        return tests;
    }

    private static void replay(JsonObject fixture, JsonArray fields) throws ReflectiveOperationException {
        var engine = new GameEngine(fixture.get("rule").getAsString(), fixture.get("mode").getAsString(),
                fixture.get("seed").getAsString(), fixture.get("first").getAsInt(), new String[]{"A","B"});
        assertEquals(fixture.get("initialDecks"), GSON.toJsonTree(engine.snapshot().initialDecks));
        assertEquals(fixture.get("initial"), project(engine.snapshot(), fields), "开局状态");
        int step = 0;
        for (var entry : fixture.getAsJsonArray("steps")) {
            var item = entry.getAsJsonObject();
            var action = item.getAsJsonObject("action");
            var before = engine.snapshot();
            String type = action.get("type").getAsString();
            var command = type.equals("play")
                    ? new MatchState.Action("play", before.active,
                        before.players[before.active].hand.get(action.get("index").getAsInt()).instanceId(),
                        action.get("lane").getAsInt(), action.get("sacrifice").getAsInt(), before.version, false)
                    : new MatchState.Action("end", before.active, 0, -1, -1, before.version, action.get("blocked").getAsBoolean());
            engine.apply(command);
            var after = engine.snapshot();
            assertEquals(item.get("expected"), project(after, fields), "第" + (++step) + "步：" + action);
            assertEquals(before.version + 1, after.version, "每条成功命令只增加一个版本");
            assertConservation(after);
        }
        assertTrue(engine.snapshot().over, "夹具必须覆盖到完整终局");
    }

    private static JsonObject project(MatchState state, JsonArray fields) throws ReflectiveOperationException {
        var result = new JsonObject();
        result.addProperty("active", state.active); result.addProperty("balance", state.balance);
        result.addProperty("over", state.over); result.add("winner", GSON.toJsonTree(state.winner));
        var players = new JsonArray();
        for (var player : state.players) {
            var p = new JsonObject();
            p.add("deck", ids(player.deck)); p.add("hand", ids(player.hand)); p.add("grave", ids(player.grave));
            var board = new JsonArray();
            for (var unit : player.board) {
                if (unit == null) { board.add(JsonNull.INSTANCE); continue; }
                var u = new JsonObject(); u.addProperty("id", unit.card.id()); u.addProperty("hp", unit.hp);
                u.addProperty("enteredTurn", unit.enteredTurn); u.addProperty("anxiety", unit.anxiety); board.add(u);
            }
            p.add("board", board);
            for (var field : fields) {
                String name = field.getAsString();
                p.addProperty(name, MatchState.Player.class.getField(name).getInt(player));
            }
            players.add(p);
        }
        result.add("players", players); return result;
    }

    private static JsonArray ids(List<MatchState.Card> cards) {
        var result = new JsonArray(); for (var card : cards) result.add(card.id()); return result;
    }

    private static void assertConservation(MatchState state) {
        var ids = new HashSet<Long>();
        for (int faction = 0; faction < 2; faction++) {
            var player = state.players[faction];
            var cards = new ArrayList<>(player.deck); cards.addAll(player.hand); cards.addAll(player.grave);
            for (var unit : player.board) if (unit != null) {
                cards.add(unit.card);
                assertTrue(Cards.get(unit.card.id()).type() == CardDefinition.Type.RESOURCE ? unit.hp == 0 : unit.hp > 0);
            }
            assertEquals(20 + (faction != state.first && player.turn > 0 ? 1 : 0), cards.size(), "牌张守恒");
            for (var card : cards) assertTrue(ids.add(card.instanceId()), "实例不能重复进入多个牌区");
            assertTrue(player.mana >= 0 && player.mana <= 10); assertTrue(player.reserve >= 0 && player.reserve <= 2);
            assertEquals(player.deathTriggered, player.deathPending + player.deathReceived + player.deathLost, "亡语收益守恒");
        }
    }
}
