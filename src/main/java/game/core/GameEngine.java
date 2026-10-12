package game.core;

import java.util.*;
import static game.core.CardDefinition.Type.*;
import static game.core.MatchState.*;

/** 纯 Java 权威规则；UI、网络和序列化均在外层。每次命令先完整校验，再修改状态。 */
public final class GameEngine {
    private final MatchState s;
    public GameEngine(String rule, String mode, String seed, int first, String[] names) {
        this(rule,mode,seed,first,names,null);
    }
    public GameEngine(String rule, String mode, String seed, int first, String[] names, List<List<String>> decks) {
        this(rule,mode,seed,first,names,decks,0);
    }
    /** 服务端重开时延续房间版本序列，拒绝上一局延迟到达的命令。 */
    public GameEngine(String rule, String mode, String seed, int first, String[] names, List<List<String>> decks,long initialVersion) {
        if (!Set.of("F","G").contains(rule) || !Set.of("base","spells").contains(mode)
                || seed==null || seed.length()>128 || first<0 || first>1 || names==null || names.length!=2
                || Arrays.stream(names).anyMatch(n->n==null||n.isBlank()||n.length()>32)) throw new IllegalArgumentException("开局配置无效");
        if(initialVersion<0) throw new IllegalArgumentException("初始版本不能为负");
        s=new MatchState(); s.ruleSet=rule; s.mode=mode; s.seed=seed; s.first=first; s.active=first; s.names=names.clone();
        s.version=initialVersion; s.initialVersion=initialVersion;
        if(decks!=null && decks.size()!=2) throw new IllegalArgumentException("必须提供双方初始牌序");
        for(int f=0;f<2;f++) {
            var expected=Cards.deck(f,mode,seed,rule);
            var d=decks==null?expected:new ArrayList<>(decks.get(f));
            var a=new ArrayList<>(expected); var b=new ArrayList<>(d); Collections.sort(a); Collections.sort(b);
            if(!a.equals(b)) throw new IllegalArgumentException("注入牌序必须与固定牌组数量一致");
            s.initialDecks.add(new ArrayList<>(d));
            for(String id:d) s.players[f].deck.add(new Card(s.nextId++,id));
        }
        for(int k=0;k<3;k++) for(int f=0;f<2;f++) draw(f);
        log("start",rule+"规则 / "+(mode.equals("base")?"不含法术":"含法术")+"，"+Cards.faction(first)+"先手");
        begin();
    }
    // 包内场景测试及预览使用；不会提供网络导入接口。
    GameEngine(MatchState state) { s=state.copy(); }
    public MatchState snapshot() { return s.copy(); }
    public void apply(Action a) {
        if(a==null) throw new IllegalArgumentException("缺少行动");
        check(a.faction(),a.version());
        if("play".equals(a.type())) play(a);
        else if("end".equals(a.type())) end(a.blocked());
        else throw new IllegalArgumentException("未知行动");
        s.actions.add(a); s.version++;
        fastForward();
    }
    public record Preview(int balance, boolean over, Integer winner, List<Event> events) {}
    public Preview preview(Action a) {
        check(a.faction(),a.version());
        var copy=new GameEngine(s); int start=copy.s.events.size();
        // 预览只模拟这次出牌及立即结束回合；不显示抽牌结果或下一张牌。
        if(a.type().equals("play")) copy.play(a);
        else if(!a.type().equals("end")) throw new IllegalArgumentException("未知预览");
        copy.end(false);
        var events=copy.s.events.subList(start,copy.s.events.size()).stream()
                .filter(e->e.privateTo()<0 && !Set.of("draw","burn","turn_start","empty_deck","resource_grant").contains(e.type())).toList();
        return new Preview(copy.s.balance,copy.s.over,copy.s.winner,events);
    }
    public void disconnect(int faction) {
        if(!s.over) { finish(1-faction,"对手断线，本局结束（不支持续局）"); s.version++; }
    }
    private void check(int f,long version) {
        if(s.over) throw new IllegalArgumentException("对局已结束");
        if(f!=s.active) throw new IllegalArgumentException("不是你的回合");
        if(version!=s.version) throw new IllegalArgumentException("状态已更新，请重新选择后确认");
    }
    private void play(Action a) {
        var p=s.players[s.active]; var q=s.players[1-s.active];
        Card card=p.hand.stream().filter(c->c.instanceId()==a.instanceId()).findFirst()
                .orElseThrow(()->new IllegalArgumentException("这张牌不在你的手牌中"));
        var c=Cards.get(card.id());
        if(c.faction()!=s.active || (c.deathMana()>0&&!s.ruleSet.equals("G"))) throw new IllegalArgumentException("阵营或规则不匹配");
        lane(a.lane());
        if(p.mana<c.cost()) throw new IllegalArgumentException("冲动不足：需要"+c.cost()+"，剩余"+p.mana);
        if(c.type()!=SPELL) {
            if(p.board[a.lane()]!=null) throw new IllegalArgumentException("该槽已占用，不能覆盖或移动单位");
        } else {
            if(q.board[a.lane()]==null || Cards.get(q.board[a.lane()].card.id()).type()!=UNIT) throw new IllegalArgumentException("请选择敌方普通单位");
            if(c.id().equals("grief")) { lane(a.sacrifice()); if(p.board[a.sacrifice()]==null) throw new IllegalArgumentException("请选择己方在场牌作为牺牲"); }
        }
        // 至此全部目标、费用、持牌资格均已验证；哀悼不会发生半次结算。
        p.mana-=c.cost(); p.hand.remove(card);
        if(c.type()!=SPELL) {
            p.board[a.lane()]=new Unit(card,p.turn);
            if(c.type()==RESOURCE) p.resourcesPlayed++; else p.unitsPlayed++;
            log("play",Cards.faction(s.active)+"在"+column(a.lane())+"放置「"+c.name()+"」");
        } else {
            p.grave.add(card); p.spellsPlayed++;
            if(c.id().equals("anxiety")) {
                q.board[a.lane()].anxiety=q.turn+1;
                log("spell","焦虑："+name(q.board[a.lane()])+"攻击-2（最低0），至其下个己方回合战斗结束；重复仅刷新");
            } else {
                log("spell","哀悼：牺牲"+name(p.board[a.sacrifice()])+"，对"+name(q.board[a.lane()])+"造成3伤害");
                die(s.active,a.sacrifice(),"牺牲");
                q.board[a.lane()].hp-=3;
                if(q.board[a.lane()].hp<=0) die(1-s.active,a.lane(),"哀悼");
            }
        }
    }
    private void begin() {
        var p=s.players[s.active]; p.turn++; p.max=Math.min(10,p.max+1);
        p.carriedMana=Math.min(Math.min(2,p.reserve),10-p.max);
        p.resourceMana=Math.min(p.resourcePending,10-p.max-p.carriedMana);
        p.deathMana=Math.min(p.deathPending,10-p.max-p.carriedMana-p.resourceMana);
        p.reserveReceived+=p.carriedMana; p.resourceReceived+=p.resourceMana; p.deathReceived+=p.deathMana;
        p.resourceLost+=p.resourcePending-p.resourceMana; p.deathLost+=p.deathPending-p.deathMana;
        if(p.resourcePending+p.deathPending>0) log("receive","待用兑现：资源"+p.resourceMana+"，亡语"+p.deathMana+"；本次溢出作废"+(p.resourcePending-p.resourceMana+p.deathPending-p.deathMana));
        p.mana=p.max+p.carriedMana+p.resourceMana+p.deathMana; p.turnBudget=p.mana;
        p.reserve=0; p.resourcePending=0; p.deathPending=0;
        log("turn_start",Cards.faction(s.active)+"第"+p.turn+"回合：基础"+p.max+"＋储备"+p.carriedMana+"＋资源"+p.resourceMana+"＋亡语"+p.deathMana+"＝"+p.mana);
        draw(s.active);
        if(s.active!=s.first&&p.turn==1) {
            receive(s.active,new Card(s.nextId++,s.active==0?"emptiness":"hope"));
            log("resource_grant",Cards.faction(s.active)+"获得后手补偿资源卡（额外，不占牌库）");
        }
    }
    private void draw(int f) {
        var p=s.players[f];
        if(p.deck.isEmpty()) { log("empty_deck",Cards.faction(f)+"牌库已空，跳过抽牌"); return; }
        receive(f,p.deck.removeFirst());
        log("draw",Cards.faction(f)+"抽取一张牌");
    }
    private void receive(int f,Card card) {
        var p=s.players[f];
        if(p.hand.size()>=10) { p.grave.add(card); log("burn",Cards.faction(f)+"手牌已满，「"+Cards.get(card.id()).name()+"」进入弃牌区（不触发亡语）"); }
        else p.hand.add(card);
    }
    private void die(int f,int lane,String reason) {
        var p=s.players[f]; var u=p.board[lane]; if(u==null) return;
        p.board[lane]=null; p.grave.add(u.card);
        log("death",Cards.faction(f)+"的"+name(u)+"因"+reason+"离场");
        int amount=Cards.get(u.card.id()).deathMana();
        if(s.ruleSet.equals("G")&&amount>0) { p.deathPending+=amount; p.deathTriggered+=amount; log("death_mana",Cards.faction(f)+"亡语待用+"+amount+"，下个己方回合兑现"); }
    }
    private void end(boolean blocked) {
        int f=s.active; var p=s.players[f]; var q=s.players[1-f]; p.completed++;
        if(blocked) { p.blocked++; log("blocked",Cards.faction(f)+"记录本回合曾因满槽受阻"); }
        for(int l=0;l<3;l++) {
            var u=p.board[l]; if(u==null||Cards.get(u.card.id()).type()==RESOURCE) continue;
            var v=q.board[l]; int a=u.attack();
            if(v!=null) {
                if(Cards.get(v.card.id()).type()!=UNIT) throw new IllegalStateException("异常状态：敌方资源卡不应跨回合驻场");
                int b=v.attack(); // 先读取双方伤害，再同时扣血，致死也反击。
                log("battle",column(l)+"同时交战："+name(u)+"生命"+u.hp+"→"+Math.max(0,u.hp-b)+"（受"+b+"）；"+name(v)+"生命"+v.hp+"→"+Math.max(0,v.hp-a)+"（受"+a+"）");
                v.hp-=a; u.hp-=b;
                if(v.hp<=0) die(1-f,l,"战斗");
                if(u.hp<=0) die(f,l,"战斗");
            } else if(u.enteredTurn>=p.turn) log("skip_scale",name(u)+"本回合登场，可交战但不能推动天平");
            else {
                s.balance+=f==0?a:-a;
                log("scale",name(u)+"攻击空列，天平"+(f==0?"+":"-")+a+"，当前"+s.balance);
                if(Math.abs(s.balance)>=12) { finish(s.balance>0?0:1,"天平达到±12"); return; }
            }
        }
        for(int l=0;l<3;l++) if(p.board[l]!=null&&Cards.get(p.board[l].card.id()).type()==RESOURCE) {
            die(f,l,"资源结算"); p.resourcePending++;
            log("resource_settle",Cards.faction(f)+"资源待用+1，下个己方回合兑现（总量上限10）");
        }
        for(var u:p.board) if(u!=null&&u.anxiety>0&&u.anxiety<=p.turn) { u.anxiety=0; log("expire",name(u)+"焦虑结束，攻击恢复"); }
        if(p.completed>=30&&q.completed>=30) { finish(s.balance==0?null:s.balance>0?0:1,"双方各完成30回合"); return; }
        p.reserve=Math.min(2,p.mana); s.active=1-f; begin();
    }
    private void fastForward() {
        if(!s.over && Arrays.stream(s.players).allMatch(p->p.hand.isEmpty()&&p.deck.isEmpty())) {
            log("automatic","双方手牌和牌库均空，自动结算剩余回合");
            while(!s.over) { int f=s.active; end(false); s.players[f].autoCompleted++; }
        }
    }
    private void finish(Integer winner,String reason) { s.over=true; s.winner=winner; s.reason=reason; log("finish",(winner==null?"平局":Cards.faction(winner)+"获胜")+"："+reason); }
    private void log(String type,String text) { s.events.add(new Event(s.events.size()+1,s.active,s.players[s.active].turn,type,text,-1)); }
    private static void lane(int l) { if(l<0||l>2) throw new IllegalArgumentException("请选择左、中、右列之一"); }
    private static String name(Unit u) { return "「"+Cards.get(u.card.id()).name()+"」"; }
    private static String column(int l) { return new String[]{"左列","中列","右列"}[l]; }
}
