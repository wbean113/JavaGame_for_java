package game.net;

import game.core.*;
import java.util.*;

/** 白名单投影：无种子、初始牌序、对手手牌实例、内部行动历史。 */
public record GameView(String ruleSet, String mode, String[] names, int first, int active, int balance,
                       long version, boolean over, Integer winner, String reason, int viewer,
                       List<PlayerView> players, List<MatchState.Event> events) {
    public record PlayerView(List<MatchState.Card> hand, int handCount, int deckCount,
            List<MatchState.Card> grave, MatchState.Unit[] board, int mana, int max, int reserve,
            int turn, int completed, int resourcePending, int deathPending, int carriedMana,
            int resourceMana, int deathMana, int turnBudget) {}
    public static GameView of(MatchState s,int viewer) {
        var list=new ArrayList<PlayerView>();
        for(int f=0;f<2;f++) { var p=s.players[f];
            list.add(new PlayerView(f==viewer?List.copyOf(p.hand):List.of(),p.hand.size(),p.deck.size(),
                    List.copyOf(p.grave),p.board,p.mana,p.max,p.reserve,p.turn,p.completed,p.resourcePending,
                    p.deathPending,p.carriedMana,p.resourceMana,p.deathMana,p.turnBudget));
        }
        return new GameView(s.ruleSet,s.mode,s.names,s.first,s.active,s.balance,s.version,s.over,s.winner,s.reason,viewer,
                list,s.events.stream().filter(e->e.privateTo()<0||e.privateTo()==viewer).toList());
    }
}
