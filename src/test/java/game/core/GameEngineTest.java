package game.core;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import java.util.*;
import static game.core.MatchState.*;
import static org.junit.jupiter.api.Assertions.*;

/** 按交接阶段1逐项覆盖结算边界；场景只经包内构造器注入，生产网络不能导入。 */
class GameEngineTest {
    private static final Gson JSON = new Gson();
    private static MatchState scene(int first) { return new GameEngine("G","spells","test-01",first,new String[]{"甲","乙"}).snapshot(); }
    private static Card card(MatchState s, String id) { return new Card(s.nextId++, id); }
    private static void hand(MatchState s, int faction, String... ids) {
        s.players[faction].hand.clear(); for (String id : ids) s.players[faction].hand.add(card(s,id));
    }
    private static Unit put(MatchState s, int faction, int lane, String id) {
        var u = new Unit(card(s,id),0); s.players[faction].board[lane]=u; return u;
    }
    private static void play(GameEngine engine, int index, int lane, int sacrifice) {
        var s=engine.snapshot(); engine.apply(new Action("play",s.active,s.players[s.active].hand.get(index).instanceId(),lane,sacrifice,s.version,false));
    }
    private static void end(GameEngine engine) { var s=engine.snapshot(); engine.apply(new Action("end",s.active,0,-1,-1,s.version,false)); }
    private static String json(GameEngine engine) { return JSON.toJson(engine.snapshot()); }
    private static void rejectsWithoutMutation(GameEngine engine, Action action) {
        String before=json(engine); assertThrows(IllegalArgumentException.class,()->engine.apply(action)); assertEquals(before,json(engine));
    }

    @Test void fixedDecksAndRearCompensationHaveCorrectCounts() {
        assertEquals(26,Cards.all().size());
        for(String rule:List.of("F","G")) for(String mode:List.of("base","spells")) for(int first=0;first<2;first++) {
            var engine=new GameEngine(rule,mode,"seed",first,new String[]{"甲","乙"});
            var s=engine.snapshot(); assertEquals(4,s.players[first].hand.size()); assertEquals(3,s.players[1-first].hand.size());
            for(int f=0;f<2;f++) {
                var deck=s.initialDecks.get(f); assertEquals(20,deck.size());
                assertEquals(mode.equals("base")?0:2,deck.stream().filter(id->Cards.get(id).type()==CardDefinition.Type.SPELL).count());
                assertEquals(rule.equals("F")?0:mode.equals("base")?2:1,deck.stream().filter(id->Cards.get(id).deathMana()>0).count());
            }
            end(engine); s=engine.snapshot(); assertEquals(5,s.players[1-first].hand.size()); assertEquals(16,s.players[1-first].deck.size());
            assertEquals(first==0?"hope":"emptiness",s.players[1-first].hand.getLast().id());
            end(engine); end(engine); assertEquals(1,engine.snapshot().events.stream().filter(e->e.type().equals("resource_grant")).count());
        }
    }

    @Test void injectedDeckOrderValidatedAndUsedWithoutReshuffle() {
        var decks=new ArrayList<List<String>>();
        for(int f=0;f<2;f++) { var deck=new ArrayList<>(Cards.deck(f,"base","x","G")); Collections.reverse(deck); decks.add(deck); }
        var engine=new GameEngine("G","base","other",0,new String[]{"A","B"},decks);
        assertEquals(decks,engine.snapshot().initialDecks);
        assertEquals(decks.getFirst().subList(0,4),engine.snapshot().players[0].hand.stream().map(Card::id).toList());
        decks.getFirst().set(0,"hope");
        assertThrows(IllegalArgumentException.class,()->new GameEngine("G","base","x",0,new String[]{"A","B"},decks));
        assertNotEquals(decks,engine.snapshot().initialDecks);
    }

    @Test void snapshotAndScenarioConstructionDoNotShareMutableState() {
        var s=scene(0); put(s,0,0,"sloth"); var engine=new GameEngine(s);
        String original=json(engine); s.players[0].board[0].hp=1; s.players[0].hand.clear(); s.names[0]="changed";
        var copy=engine.snapshot(); copy.players[0].board[0].hp=2; copy.players[1].deck.clear(); copy.events.clear(); copy.initialDecks.get(0).clear();
        assertEquals(original,json(engine));
    }

    @Test void invalidCommandsNeverSpendOrAdvanceVersion() {
        var s=scene(0); hand(s,0,"boredom"); s.players[0].mana=5; put(s,0,1,"sloth");
        var engine=new GameEngine(s); long id=s.players[0].hand.getFirst().instanceId();
        for(var action:List.of(new Action("play",1,id,0,-1,0,false),new Action("play",0,id,0,-1,99,false),
                new Action("play",0,9999,0,-1,0,false),new Action("play",0,id,-1,-1,0,false),
                new Action("play",0,id,3,-1,0,false),new Action("play",0,id,1,-1,0,false),
                new Action("unknown",0,id,0,-1,0,false))) rejectsWithoutMutation(engine,action);
        s.players[0].mana=0; rejectsWithoutMutation(new GameEngine(s),new Action("play",0,id,0,-1,0,false));
        hand(s,0,"focus"); s.players[0].mana=5;
        rejectsWithoutMutation(new GameEngine(s),new Action("play",0,s.players[0].hand.getFirst().instanceId(),0,-1,0,false));
    }

    @Test void simultaneousLethalDamageKillsBothWithoutOverflow() {
        var s=scene(1); put(s,1,0,"focus"); put(s,0,0,"bewilderment");
        var engine=new GameEngine(s); end(engine); var after=engine.snapshot();
        assertNull(after.players[0].board[0]); assertNull(after.players[1].board[0]); assertEquals(0,after.balance);
        assertEquals(1,after.players[0].grave.size()); assertEquals(1,after.players[1].grave.size());
    }

    @Test void survivorsKeepDamageAcrossBothPlayersTurns() {
        var s=scene(0); put(s,0,0,"sloth"); put(s,1,0,"awe"); var engine=new GameEngine(s);
        end(engine); var after=engine.snapshot(); assertEquals(4,after.players[0].board[0].hp); assertEquals(2,after.players[1].board[0].hp);
        end(engine); after=engine.snapshot(); assertEquals(2,after.players[0].board[0].hp); assertNull(after.players[1].board[0]); assertEquals(0,after.balance);
    }

    @Test void zeroAttackStillTakesDamageAndRetaliationHappensOnlyOnce() {
        var s=scene(0); put(s,0,0,"boredom").anxiety=99; put(s,1,0,"calm");
        var engine=new GameEngine(s); end(engine); var after=engine.snapshot();
        assertNull(after.players[0].board[0]); assertEquals(4,after.players[1].board[0].hp); assertEquals(0,after.balance);
        s=scene(0); put(s,0,0,"sloth"); put(s,1,0,"calm").anxiety=99; engine=new GameEngine(s); end(engine); after=engine.snapshot();
        assertEquals(5,after.players[0].board[0].hp); assertEquals(1,after.players[1].board[0].hp);
    }

    @Test void freshUnitsFightImmediatelyButScoreOnlyOnNextOwnTurnForBothFactions() {
        for(int faction=0;faction<2;faction++) {
            var s=scene(faction); hand(s,faction,faction==0?"bewilderment":"peace"); var engine=new GameEngine(s);
            play(engine,0,0,-1); end(engine); assertEquals(0,engine.snapshot().balance);
            end(engine); end(engine); assertEquals(faction==0?2:-2,engine.snapshot().balance);
        }
        var s=scene(1); hand(s,1,"focus"); put(s,0,0,"bewilderment"); var engine=new GameEngine(s);
        play(engine,0,0,-1); end(engine); assertNull(engine.snapshot().players[0].board[0]); assertNull(engine.snapshot().players[1].board[0]);
    }

    @Test void spellClearedLaneDoesNotMakeNewUnitEligibleToScore() {
        var s=scene(1); s.players[1].mana=10; hand(s,1,"peace","grief"); put(s,1,1,"focus"); put(s,0,0,"fear");
        var engine=new GameEngine(s); play(engine,0,0,-1); play(engine,0,0,1); end(engine);
        assertEquals(0,engine.snapshot().balance); assertEquals(1,engine.snapshot().players[1].board[0].hp);
    }

    @Test void crossingThresholdStopsLaterLanesResourceSettlementAndTurnSwitch() {
        var s=scene(0); s.balance=11; put(s,0,0,"boredom"); put(s,0,1,"emptiness"); put(s,0,2,"anger"); put(s,1,2,"focus");
        var engine=new GameEngine(s); end(engine); var after=engine.snapshot();
        assertTrue(after.over); assertEquals(0,after.winner); assertEquals(12,after.balance); assertEquals(0,after.active);
        assertNotNull(after.players[0].board[1]); assertEquals(0,after.players[0].resourcePending);
        assertEquals(3,after.players[0].board[2].hp); assertEquals(2,after.players[1].board[2].hp);
        assertThrows(IllegalArgumentException.class,()->end(engine));
    }

    @Test void thirtyTurnsDrawEmptyDeckAndFullHandAreHandledWithoutFatigue() {
        var engine=new GameEngine("G","base","x",0,new String[]{"A","B"});
        for(int n=0;n<60;n++) end(engine);
        var s=engine.snapshot(); assertTrue(s.over); assertNull(s.winner); assertEquals(0,s.balance);
        for(int f=0;f<2;f++) {
            var p=s.players[f]; assertEquals(30,p.completed); assertEquals(10,p.max); assertEquals(10,p.mana);
            assertEquals(10,p.hand.size()); assertTrue(p.deck.isEmpty()); assertEquals(f==0?10:11,p.grave.size()); assertEquals(0,p.deathTriggered);
        }
    }

    @Test void thirtyTurnsRequireBothPlayersAndUseBalanceForWinner() {
        var s=scene(0); s.players[0].completed=29; s.players[1].completed=29; s.balance=-3;
        var engine=new GameEngine(s); end(engine); assertFalse(engine.snapshot().over); end(engine);
        assertTrue(engine.snapshot().over); assertEquals(1,engine.snapshot().winner);
    }

    @Test void repeatedAnxietyRefreshesWithoutStackingAndExpiresAfterVictimsCombat() {
        var s=scene(0); hand(s,0,"anxiety","anxiety"); s.players[0].mana=4; put(s,1,0,"calm");
        var engine=new GameEngine(s); play(engine,0,0,-1); play(engine,0,0,-1);
        assertEquals(1,engine.snapshot().players[1].board[0].attack()); end(engine);
        assertEquals(1,engine.snapshot().players[1].board[0].attack()); end(engine);
        assertEquals(-1,engine.snapshot().balance); assertEquals(3,engine.snapshot().players[1].board[0].attack());
        var zero=new Unit(new Card(999,"focus"),0); zero.anxiety=1; assertEquals(0,zero.attack());
    }

    @Test void griefValidatesEveryTargetBeforeAnyCostSacrificeOrDeathTrigger() {
        var s=scene(1); hand(s,1,"grief"); s.players[1].mana=2; put(s,1,0,"relief"); put(s,0,0,"aftershock"); put(s,0,2,"emptiness");
        var engine=new GameEngine(s); long id=s.players[1].hand.getFirst().instanceId();
        for(int[] targets:new int[][]{{1,0},{0,1},{0,-1},{0,3},{2,0},{3,0}})
            rejectsWithoutMutation(engine,new Action("play",1,id,targets[0],targets[1],0,false));
        play(engine,0,0,0); var after=engine.snapshot(); assertEquals(0,after.players[1].mana);
        assertNull(after.players[0].board[0]); assertNull(after.players[1].board[0]); assertEquals(0,after.balance);
        assertEquals(1,after.players[0].deathTriggered); assertEquals(1,after.players[1].deathTriggered);
        assertEquals(2,after.players[1].grave.size());
    }

    @Test void resourceZeroHealthSurvivesPlacementBlocksSlotAndDoesNotFight() {
        var s=scene(1); hand(s,1,"hope","focus"); s.players[1].mana=1; put(s,0,0,"fear");
        var engine=new GameEngine(s); play(engine,0,0,-1); assertEquals(0,engine.snapshot().players[1].board[0].hp);
        var after=engine.snapshot(); rejectsWithoutMutation(engine,new Action("play",1,after.players[1].hand.getFirst().instanceId(),0,-1,after.version,false));
        end(engine); after=engine.snapshot(); assertNull(after.players[1].board[0]); assertEquals(2,after.players[0].board[0].hp);
        assertEquals(0,after.balance); assertEquals(1,after.players[1].resourcePending); assertEquals(0,after.players[1].resourceReceived);
    }

    @Test void resourceSacrificedEarlyNeverGrantsBonus() {
        var s=scene(1); hand(s,1,"hope","grief"); s.players[1].mana=2; put(s,0,2,"fear");
        var engine=new GameEngine(s); play(engine,0,0,-1); play(engine,0,2,0); end(engine); end(engine);
        var p=engine.snapshot().players[1]; assertEquals(0,p.resourceReceived); assertEquals(0,p.resourcePending); assertEquals(0,p.deathTriggered);
    }

    @Test void ownTurnDeathQueuesOnceAndPaysOnlyOnNextOwnerTurn() {
        var s=scene(0); put(s,0,0,"aftershock"); put(s,1,0,"persistence"); s.players[0].mana=0;
        var engine=new GameEngine(s); end(engine); var p=engine.snapshot().players[0];
        assertEquals(1,p.deathPending); assertEquals(1,p.deathTriggered); assertEquals(0,p.deathReceived); assertEquals(0,p.mana); assertEquals(0,p.reserve);
        end(engine); p=engine.snapshot().players[0]; assertEquals(3,p.mana); assertEquals(1,p.deathReceived); assertEquals(0,p.deathPending);
        assertEquals(1,p.grave.stream().filter(c->c.id().equals("aftershock")).count());
    }

    @Test void defendingDeathPaysOnImmediatelyFollowingOwnerTurn() {
        var s=scene(1); s.players[0].turn=1; s.players[0].max=1; s.players[0].reserve=1;
        put(s,0,0,"aftershock"); put(s,1,0,"persistence"); var engine=new GameEngine(s); end(engine);
        var p=engine.snapshot().players[0]; assertEquals(2,p.turn); assertEquals(4,p.mana); assertEquals(1,p.deathReceived); assertEquals(0,p.deathPending);
    }

    @Test void simultaneousDeathsPayEachOwnerOnceAtTheirOwnTurn() {
        var s=scene(0); put(s,0,0,"aftershock").hp=1; put(s,1,0,"relief").hp=1;
        var engine=new GameEngine(s); end(engine); var after=engine.snapshot();
        assertEquals(1,after.players[0].deathPending); assertEquals(0,after.players[1].deathPending);
        assertEquals(1,after.players[1].deathReceived); assertEquals(2,after.players[1].mana); assertEquals(0,after.balance);
        end(engine); assertEquals(1,engine.snapshot().players[0].deathReceived);
    }

    @Test void fullHandBurnAndFullHandCompensationDoNotTriggerDeathEffects() {
        var s=scene(0); var p=s.players[1]; p.hand.clear(); for(int n=0;n<10;n++) p.hand.add(card(s,"focus"));
        p.deck.clear(); p.deck.add(card(s,"relief")); var engine=new GameEngine(s); end(engine); p=engine.snapshot().players[1];
        assertEquals(10,p.hand.size()); assertEquals(List.of("relief","hope"),p.grave.stream().map(Card::id).toList());
        assertEquals(0,p.deathTriggered); assertEquals(0,p.resourcePending);
    }

    @Test void resourcePriorityAndOverflowClearAllPendingAndNeverPayTwice() {
        for(int previousMax:new int[]{2,5,6,7,8,9,10}) {
            var s=scene(0); var p=s.players[1]; p.turn=1; p.max=previousMax; p.reserve=2; p.resourcePending=2; p.deathPending=2; p.deathTriggered=2;
            var engine=new GameEngine(s); end(engine); p=engine.snapshot().players[1];
            int base=Math.min(10,previousMax+1),reserve=Math.min(2,10-base),resource=Math.min(2,10-base-reserve),death=Math.min(2,10-base-reserve-resource);
            assertEquals(base+reserve+resource+death,p.mana); assertEquals(reserve,p.carriedMana); assertEquals(resource,p.resourceMana); assertEquals(death,p.deathMana);
            assertEquals(2-resource,p.resourceLost); assertEquals(2-death,p.deathLost); assertEquals(0,p.reserve); assertEquals(0,p.resourcePending); assertEquals(0,p.deathPending);
            end(engine); end(engine); p=engine.snapshot().players[1]; assertEquals(resource,p.resourceReceived); assertEquals(death,p.deathReceived);
        }
    }

    @Test void endTurnResourceStacksWithReserveAndMultipleResources() {
        var s=scene(1); s.players[1].max=2; s.players[1].mana=2; put(s,1,0,"hope"); put(s,1,1,"hope");
        var engine=new GameEngine(s); end(engine); var p=engine.snapshot().players[1]; assertEquals(2,p.reserve); assertEquals(2,p.resourcePending);
        end(engine); p=engine.snapshot().players[1]; assertEquals(7,p.mana); assertEquals(2,p.resourceReceived); assertEquals(0,p.resourcePending);
    }

    @Test void fRejectsGDeathUnitsBeforeSpending() {
        var s=scene(0); s.ruleSet="F"; hand(s,0,"aftershock"); s.players[0].mana=2;
        rejectsWithoutMutation(new GameEngine(s),new Action("play",0,s.players[0].hand.getFirst().instanceId(),0,-1,0,false));
    }

    @Test void previewIsPureAndMatchesCombatWithoutShowingDraws() {
        var s=scene(1); hand(s,1,"hope"); put(s,1,1,"relief").hp=1; put(s,0,1,"aftershock").hp=1;
        var engine=new GameEngine(s); String before=json(engine);
        var action=new Action("play",1,s.players[1].hand.getFirst().instanceId(),0,-1,0,false);
        var preview=engine.preview(action); assertEquals(before,json(engine));
        assertTrue(preview.events().stream().anyMatch(e->e.type().equals("resource_settle")));
        assertEquals(2,preview.events().stream().filter(e->e.type().equals("death_mana")).count());
        assertTrue(preview.events().stream().noneMatch(e->Set.of("draw","turn_start","empty_deck","resource_grant","burn").contains(e.type())));
        engine.apply(action); end(engine); assertEquals(preview.balance(),engine.snapshot().balance); assertEquals(preview.winner(),engine.snapshot().winner);
    }

    @Test void previewMustNotRevealNextBurnedCard() {
        var s=scene(0); s.players[1].hand.clear(); for(int n=0;n<10;n++) s.players[1].hand.add(card(s,"focus"));
        s.players[1].deck.clear(); s.players[1].deck.add(card(s,"domination"));
        var engine=new GameEngine(s); String before=json(engine); var preview=engine.preview(new Action("end",0,0,-1,-1,0,false));
        assertEquals(before,json(engine)); assertTrue(preview.events().stream().noneMatch(e->e.text().contains("支配")),"预览不得透露对方下一张烧掉的牌");
    }

    @Test void emptyHandsAndDecksAutomaticallyFinishWithCorrectDeathTiming() {
        var s=scene(0); for(var p:s.players) { p.hand.clear(); p.deck.clear(); }
        // 使用后手已过首回合的局面，避免换方时额外获赠资源卡使「双方无手牌」不成立。
        s.players[1].turn=1; s.players[1].max=1;
        put(s,0,0,"aftershock").hp=1; put(s,1,0,"relief").hp=1;
        var engine=new GameEngine(s); end(engine); var after=engine.snapshot();
        assertTrue(after.over); assertNull(after.winner); assertEquals(0,after.balance);
        assertEquals(30,after.players[0].completed); assertEquals(30,after.players[1].completed);
        assertEquals(29,after.players[0].autoCompleted); assertEquals(30,after.players[1].autoCompleted);
        assertEquals(1,after.players[0].deathReceived); assertEquals(1,after.players[1].deathReceived);
    }
}
