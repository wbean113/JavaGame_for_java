package game.core;

/** 不可变卡牌定义。资源牌的 0 生命与普通单位死亡是不同概念。 */
public record CardDefinition(String id, String name, int faction, Type type,
                             int cost, int attack, int health, int deathMana, String text) {
    public enum Type { UNIT, SPELL, RESOURCE }
    public CardDefinition {
        if (id == null || name == null || type == null || faction < 0 || faction > 1 || cost < 0
                || attack < 0 || health < 0 || (type == Type.UNIT && health == 0))
            throw new IllegalArgumentException("无效卡牌定义");
    }
}
