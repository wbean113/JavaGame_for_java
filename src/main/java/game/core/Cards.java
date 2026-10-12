package game.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Cards {
    private static final Map<String, CardDefinition> ALL = load();
    private Cards() {}
    private static Map<String, CardDefinition> load() {
        var result = new LinkedHashMap<String, CardDefinition>();
        try (var reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(Cards.class.getResourceAsStream("/cards.tsv")), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("#") || line.isBlank()) continue;
                String[] c = line.split("\t", -1);
                var card = new CardDefinition(c[0], c[1], Integer.parseInt(c[2]), CardDefinition.Type.valueOf(c[3]),
                        Integer.parseInt(c[4]), Integer.parseInt(c[5]), Integer.parseInt(c[6]), Integer.parseInt(c[7]), c[8]);
                if (result.put(card.id(), card) != null) throw new IllegalArgumentException("卡牌ID重复");
            }
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
        if (result.size() != 26) throw new IllegalStateException("卡池应包含26种定义");
        return Collections.unmodifiableMap(result);
    }
    public static CardDefinition get(String id) {
        var c = ALL.get(id);
        if (c == null) throw new IllegalArgumentException("未知卡牌：" + id);
        return c;
    }
    public static Collection<CardDefinition> all() { return ALL.values(); }
    public static String faction(int f) { return f == 0 ? "努斯" : "逻各斯"; }

    /** 与 JS 原型逐位相同的 FNV 混合 + mulberry32 + Fisher-Yates。 */
    public static List<String> deck(int faction, String mode, String seed, String rule) {
        var list = new ArrayList<String>();
        for (var c : all()) {
            if (c.faction() != faction || c.type() != CardDefinition.Type.UNIT || c.deathMana() != 0) continue;
            int count = mode.equals("spells") && Set.of("irritation", "envy", "clarity", "responsibility").contains(c.id()) ? 1 : 2;
            for (int i = 0; i < count; i++) list.add(c.id());
        }
        if (mode.equals("spells")) { list.add(faction == 0 ? "anxiety" : "grief"); list.add(faction == 0 ? "anxiety" : "grief"); }
        int hash = 0x811c9dc5;
        // JS for-of 按码点迭代，但 charCodeAt(0) 取该码点的首个 UTF-16 单元。
        String key = seed + ":" + faction;
        for (int pos = 0; pos < key.length();) {
            hash = (hash ^ key.charAt(pos)) * 16777619;
            pos += Character.charCount(key.codePointAt(pos));
        }
        for (int i = list.size() - 1; i > 0; i--) {
            hash += 0x6D2B79F5;
            int t = hash;
            t = (t ^ (t >>> 15)) * (t | 1);
            t ^= t + (t ^ (t >>> 7)) * (t | 61);
            double r = Integer.toUnsignedLong(t ^ (t >>> 14)) / 4294967296.0;
            Collections.swap(list, i, (int) (r * (i + 1)));
        }
        if (rule.equals("G")) list.replaceAll(id -> id.equals("irritation") ? "aftershock" : id.equals("clarity") ? "relief" : id);
        return list;
    }
}
