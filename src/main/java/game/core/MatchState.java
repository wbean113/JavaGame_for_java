package game.core;

import java.util.*;

/** 状态快照：引擎只返回深复制；网络层绝不能把这个完整对象直接广播。 */
public final class MatchState {
    public String ruleSet, mode, seed;
    public String[] names;
    public int first, active, balance;
    public long version, initialVersion, nextId = 1;
    public boolean over;
    public Integer winner;
    public String reason = "";
    public List<List<String>> initialDecks = new ArrayList<>();
    public Player[] players = {new Player(), new Player()};
    public List<Event> events = new ArrayList<>();
    public List<Action> actions = new ArrayList<>();

    public record Card(long instanceId, String id) {}
    public static final class Unit {
        public Card card;
        public int hp, enteredTurn, anxiety;
        public Unit(Card card, int turn) { this.card = card; this.hp = Cards.get(card.id()).health(); this.enteredTurn = turn; }
        public int attack() { return Math.max(0, Cards.get(card.id()).attack() - (anxiety > 0 ? 2 : 0)); }
        public Unit copy() { var u = new Unit(card, enteredTurn); u.hp = hp; u.anxiety = anxiety; return u; }
    }
    public static final class Player {
        public List<Card> deck = new ArrayList<>(), hand = new ArrayList<>(), grave = new ArrayList<>();
        public Unit[] board = new Unit[3];
        public int mana, max, reserve, turnBudget, turn, completed;
        public int resourcePending, deathPending, reserveReceived, resourceReceived, resourceLost, deathReceived, deathLost, deathTriggered;
        public int carriedMana, resourceMana, deathMana, blocked, unitsPlayed, spellsPlayed, resourcesPlayed, autoCompleted;
        Player copy() {
            var p = new Player();
            p.deck.addAll(deck); p.hand.addAll(hand); p.grave.addAll(grave);
            for (int i=0;i<3;i++) p.board[i]=board[i]==null?null:board[i].copy();
            p.mana=mana; p.max=max; p.reserve=reserve; p.turnBudget=turnBudget; p.turn=turn; p.completed=completed;
            p.resourcePending=resourcePending; p.deathPending=deathPending; p.reserveReceived=reserveReceived;
            p.resourceReceived=resourceReceived; p.resourceLost=resourceLost; p.deathReceived=deathReceived;
            p.deathLost=deathLost; p.deathTriggered=deathTriggered; p.carriedMana=carriedMana; p.resourceMana=resourceMana;
            p.deathMana=deathMana; p.blocked=blocked; p.unitsPlayed=unitsPlayed; p.spellsPlayed=spellsPlayed;
            p.resourcesPlayed=resourcesPlayed; p.autoCompleted=autoCompleted;
            return p;
        }
    }
    /** privateTo=-1 为公开事件；抽牌具体内容只对本人及终局记录可见。 */
    public record Event(int step, int active, int turn, String type, String text, int privateTo) {}
    public record Action(String type, int faction, long instanceId, int lane, int sacrifice, long version, boolean blocked) {}
    public MatchState copy() {
        var s=new MatchState(); s.ruleSet=ruleSet; s.mode=mode; s.seed=seed; s.names=names.clone();
        s.first=first; s.active=active; s.balance=balance; s.version=version; s.initialVersion=initialVersion; s.nextId=nextId;
        s.over=over; s.winner=winner; s.reason=reason;
        for(var d:initialDecks) s.initialDecks.add(new ArrayList<>(d));
        s.players=new Player[]{players[0].copy(),players[1].copy()};
        s.events.addAll(events); s.actions.addAll(actions); return s;
    }
}
