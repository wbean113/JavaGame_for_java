package game.client;

import com.google.gson.*;
import game.core.*;
import game.net.*;
import javafx.application.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** 同机双人和联网共用界面；画面只读取 GameView，不接触对手隐藏信息。 */
public final class GameApp extends Application {
    private Stage stage;
    private final BorderPane root=new BorderPane();
    private final Label status=new Label("选择同机试玩，或连接服务器创建 / 加入房间。");
    private final TextArea notes=new TextArea();
    private final TextArea preview=new TextArea();
    private final TextArea log=new TextArea();
    private final VBox arena=new VBox(10), handArea=new VBox(8);
    private final TitledPane setup=new TitledPane();
    private final Button confirm=button("确认出牌","confirm",()->act("play"));
    private final Button end=button("结束回合 · 结算战斗","end",()->act("end"));
    private final Button ready=button("准备 / 再来一局","ready",()->send(Map.of("type","ready")));
    private final CheckBox blocked=new CheckBox("本回合曾满槽受阻（仅记录）");
    private final TextField localNous=new TextField("玩家一"), localLogos=new TextField("玩家二"), seed=new TextField("test-01");
    private final TextField host=new TextField("127.0.0.1"), port=new TextField("23456"), nickname=new TextField("玩家"), roomCode=new TextField();
    private final ComboBox<String> rule=new ComboBox<>(), mode=new ComboBox<>(), first=new ComboBox<>();
    private GameEngine local;
    private GameConnection connection;
    private GameView view;
    private boolean hidden, pending, disconnected;
    private long selected=-1, previewId, generation;
    private int lane=-1,sacrifice=-1;
    private String room="";
    private Path exportDestination;

    @Override public void start(Stage stage) {
        this.stage=stage;
        rule.getItems().addAll("G · 死亡回费","F · 对照规则");rule.getSelectionModel().selectFirst();
        mode.getItems().addAll("含法术","不含法术");mode.getSelectionModel().selectFirst();
        first.getItems().addAll("努斯先手","逻各斯先手");first.getSelectionModel().selectFirst();
        rule.setId("rule"); mode.setId("mode"); first.setId("first"); seed.setId("seed");
        host.setId("host"); port.setId("port"); roomCode.setId("room-code"); nickname.setId("nickname");
        root.setTop(header());
        var content=new VBox(14,arena,handArea); content.setPadding(new Insets(12,16,12,16));
        var scroll=new ScrollPane(content); scroll.setFitToWidth(true);
        var center=new BorderPane(scroll);var actionBar=actions();BorderPane.setMargin(actionBar,new Insets(10,16,4,16));center.setBottom(actionBar);root.setCenter(center);
        var side=new VBox(8,title("行动预览",17),preview,title("战斗记录",17),log,title("试玩笔记",17),notes,
                button("导出 JSON 记录","export",this::export));
        side.setPadding(new Insets(16,16,16,0)); side.setPrefWidth(325); side.setMinWidth(260);
        preview.setEditable(false); preview.setWrapText(true); preview.setPrefRowCount(7); preview.setId("preview");
        log.setEditable(false); log.setWrapText(true); VBox.setVgrow(log,Priority.ALWAYS); log.setId("battle-log");
        notes.setWrapText(true); notes.setPromptText("记录规则疑问、满槽体验或平衡建议……"); notes.setPrefRowCount(3); notes.setId("notes");
        root.setRight(side); status.setWrapText(true); status.setId("status"); status.getStyleClass().add("status");
        root.setBottom(status); BorderPane.setMargin(status,new Insets(8,16,14,16));
        var scene=new Scene(root,1280,900); scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/theme.css")).toExternalForm());
        stage.setTitle("心理天平 · Java 双人卡牌"); stage.setScene(scene);stage.setMinWidth(1040);stage.setMinHeight(720);
        stage.setOnCloseRequest(e->closeConnection()); render();stage.show();
    }
    private Node header() {
        var top=new HBox(14,title("心理天平",26),title("三列之间，选择你的倾向",14),spacer(),ready,button("规则帮助","help",this::help));
        top.setAlignment(Pos.CENTER_LEFT);
        var config=new FlowPane(10,8,new Label("新局配置"),rule,mode,first);
        var localRow=new FlowPane(8,8,new Label("同机双人"),field(localNous,105),field(localLogos,105),new Label("种子"),field(seed,125),button("开始同机对局","local-start",this::startLocal));
        roomCode.setPromptText("6位房间号");
        var network=new FlowPane(8,8,new Label("局域网"),field(host,125),field(port,65),new Label("名字"),field(nickname,100),
                button("连接并建房","create",()->connect(true)),field(roomCode,100),button("连接并加入","join",()->connect(false)),
                button("断开连接","disconnect",this::disconnect));
        setup.setText("开局与联机设置 · 点击展开 / 收起");setup.setContent(new VBox(10,config,localRow,network));setup.setAnimated(false);
        var box=new VBox(8,top,setup);box.setPadding(new Insets(12,16,10,16)); box.getStyleClass().add("header");return box;
    }
    private Node actions() {
        var cancel=button("取消选择","cancel",()->{clearSelection();render();});
        var forecast=button("预览直接结束回合","end-preview",()->requestPreview("end"));
        return new VBox(8,new FlowPane(10,8,confirm,cancel,forecast,end),blocked);
    }
    private String selectedRule() { return rule.getSelectionModel().getSelectedIndex()==0?"G":"F"; }
    private String selectedMode() { return mode.getSelectionModel().getSelectedIndex()==0?"spells":"base"; }
    private void startLocal() {
        try {
            var next=new GameEngine(selectedRule(),selectedMode(),seed.getText(),first.getSelectionModel().getSelectedIndex(),new String[]{localNous.getText(),localLogos.getText()});
            closeConnection();local=next;room="";hidden=true;pending=false;disconnected=false;notes.clear();setup.setExpanded(false);clearSelection();refreshLocal();
            message("对局已开始。请将屏幕交给先手玩家，再查看手牌。");
        } catch(Exception e) { message(e.getMessage()); }
    }
    private void refreshLocal() { var s=local.snapshot(); view=GameView.of(s,s.active);render(); }
    private void connect(boolean create) {
        String address=host.getText().strip(), name=nickname.getText().strip(), code=roomCode.getText().strip();
        int number;
        try { number=Integer.parseInt(port.getText()); if(number<1||number>65535)throw new NumberFormatException(); }
        catch(NumberFormatException e) { message("端口必须为1～65535的整数");return; }
        if(name.isBlank()||name.length()>32) { message("玩家名字需要1～32个字符");return; }
        closeConnection();local=null;view=null;pending=true;disconnected=false;hidden=false;clearSelection();render();
        long token=generation;
        var request=new LinkedHashMap<String,Object>(); request.put("type",create?"create":"join");request.put("name",name);
        if(create) {request.put("rule",selectedRule());request.put("mode",selectedMode());request.put("first",first.getSelectionModel().getSelectedIndex());} else request.put("room",code);
        message("正在连接 "+address+":"+number+"……");
        Thread.ofVirtual().start(()->{
            try {
                var next=new GameConnection(address,number,m->Platform.runLater(()->{if(token==generation)receive(m);}),e->Platform.runLater(()->{if(token==generation){disconnected=true;pending=false;message(e);render();}}));
                Platform.runLater(()->{if(token!=generation){next.close();return;}connection=next;send(request);});
            } catch(Exception e) { Platform.runLater(()->{if(token==generation){pending=false;disconnected=true;message("连接失败："+e.getMessage());render();}}); }
        });
    }
    private void receive(JsonObject m) {
        switch(m.get("type").getAsString()) {
            case "state" -> {
                pending=false;room=m.get("room").getAsString();roomCode.setText(room);hidden=false;
                var previous=view;
                view=m.has("view")?Json.GSON.fromJson(m.get("view"),GameView.class):null;
                if(view!=null)setup.setExpanded(false);
                if(view==null||previous==null||view.version()!=previous.version()||view.over()!=previous.over()) clearSelection();
                var names=m.getAsJsonArray("names");var flags=m.getAsJsonArray("ready");
                String detail="房间 "+room+" · 你是"+Cards.faction(m.get("faction").getAsInt())+" ｜ "+names.get(0).getAsString()+(flags.get(0).getAsBoolean()?" ✓准备":"")+" / "+names.get(1).getAsString()+(flags.get(1).getAsBoolean()?" ✓准备":"");
                message(detail+(view==null?"。双方点击准备后开始。":""));render();
            }
            case "error" -> { pending=false;confirm.setDisable(true);message(m.get("message").getAsString());renderButtons(); }
            case "preview" -> {
                if(view!=null&&m.get("version").getAsLong()==view.version()&&m.get("requestId").getAsLong()==previewId)
                    showPreview(Json.GSON.fromJson(m.get("preview"),GameEngine.Preview.class));
            }
            case "export" -> saveRecord(m.get("record"));
            default -> { }
        }
    }
    private void render() {
        arena.getChildren().clear();handArea.getChildren().clear();
        ready.setDisable(connection==null||disconnected||(view!=null&&!view.over()));
        if(view==null) {
            arena.getChildren().addAll(title("让情绪落在棋盘上",24),label("每方三列，结束回合时逐列交战。努斯向 +12 推进，逻各斯向 −12 推进。\n先开始一局同机试玩，或启动服务器后创建双人房间。"));
            log.clear();renderButtons();return;
        }
        String turn=view.over()?(view.winner()==null?"平局":Cards.faction(view.winner())+"获胜"):
                view.names()[view.active()]+" · "+Cards.faction(view.active())+"的回合";
        var heading=new HBox(title(turn,21),spacer(),title(view.ruleSet()+" / "+(view.mode().equals("base")?"不含法术":"含法术"),14));
        var scale=new ProgressBar(Math.max(0,Math.min(1,(view.balance()+12)/24.0)));scale.setMaxWidth(Double.MAX_VALUE);scale.setId("scale");
        arena.getChildren().addAll(heading,new HBox(title("逻各斯 −12",14),spacer(),title("天平 "+String.format("%+d",view.balance()),20),spacer(),title("+12 努斯",14)),scale);
        if(view.over()) arena.getChildren().add(label(view.reason()+"。可以导出记录，或开始新局。"));
        int me=view.viewer();arena.getChildren().add(playerBoard(1-me,false));arena.getChildren().add(playerBoard(me,true));
        handArea.getChildren().add(title("你的手牌 · "+Cards.faction(me),17));
        if(hidden&&!view.over()) {
            var reveal=button("我已接手 · 查看手牌","reveal",()->{hidden=false;message("先选手牌，再选目标；确认出牌与结束回合是两个操作。");render();});
            handArea.getChildren().addAll(label("请交给 "+view.names()[me]+"。手牌已遮住。"),reveal);
        } else {
            var hand=new FlowPane(8,8);hand.setId("hand");
            for(var c:view.players().get(me).hand()) {
                var def=Cards.get(c.id());
                String why=def.cost()>view.players().get(me).mana()?"冲动不足":"点击选牌";
                var card=button(def.name()+"  "+def.cost()+"费\n"+stats(def)+"\n"+why,"hand-"+c.instanceId(),()->select(c));
                card.setPrefSize(126,96);card.getStyleClass().add(def.faction()==0?"nous-card":"logos-card");
                if(c.instanceId()==selected)card.getStyleClass().add("selected");
                card.setTooltip(new Tooltip(def.name()+"\n"+def.text()));
                // 不把费用不足牌变成无法解释的灰块，点击会显示具体原因。
                card.setDisable(!canAct());var art=getClass().getResource("/cards/"+def.id()+".png");
                if(art!=null){var image=new ImageView(new Image(art.toExternalForm(),64,48,true,true));card.setGraphic(image);card.setContentDisplay(ContentDisplay.TOP);}
                hand.getChildren().add(card);
            }
            if(hand.getChildren().isEmpty())hand.getChildren().add(label("暂无手牌。牌库为空后不造成疲劳伤害。"));
            handArea.getChildren().add(hand);
        }
        log.setText(view.events().stream().map(e->e.step()+"  "+e.text()).collect(Collectors.joining("\n")));log.positionCaret(log.getLength());
        renderButtons();
    }
    private Node playerBoard(int faction,boolean own) {
        var p=view.players().get(faction);
        var title=title(view.names()[faction]+" · "+Cards.faction(faction)+(own?"（你）":"")+"  回合 "+p.turn()+" / 已完成 "+p.completed(),16);
        var info=label("冲动 "+p.mana()+" / 本回合 "+p.turnBudget()+"    基础 "+p.max()+" + 储备 "+p.carriedMana()+" + 资源 "+p.resourceMana()+" + 亡语 "+p.deathMana()+
                "\n下次待用：储备 "+p.reserve()+" / 资源 "+p.resourcePending()+" / 亡语 "+p.deathPending()+"    牌库 "+p.deckCount()+" · 手牌 "+p.handCount()+" · 弃牌 "+p.grave().size());
        var row=new HBox(8);
        for(int col=0;col<3;col++) {
            int index=col;var u=p.board()[col];
            String text=new String[]{"左列 0","中列 1","右列 2"}[col]+"\n";
            if(u==null)text+="空槽\n"+(own?"单位 / 资源放置于此":"等待对位单位");
            else {var c=Cards.get(u.card.id());text+=c.name()+"  "+c.cost()+"费\n";
                if(c.type()==CardDefinition.Type.RESOURCE)text+="资源牌 · 不攻击\n回合末离场，资源待用 +1";
                else text+="攻击 "+u.attack()+"    当前生命 "+u.hp+" / "+c.health()+"\n"+(u.enteredTurn>=p.turn()?"新登场：可交战，不能打天平":"可推动天平")+(u.anxiety>0?"\n焦虑：至己方第"+u.anxiety+"回合战斗后":"")+(c.deathMana()>0?"\n亡语：下个己方回合 +1":"");
            }
            var cell=button(text,"board-"+faction+"-"+col,()->target(own,index));cell.setWrapText(true);cell.setMaxWidth(Double.MAX_VALUE);cell.setPrefHeight(110);cell.setMinHeight(110);HBox.setHgrow(cell,Priority.ALWAYS);
            cell.getStyleClass().add(faction==0?"nous-card":"logos-card");
            boolean spell=selectedDefinition()!=null&&selectedDefinition().type()==CardDefinition.Type.SPELL;
            if((own&&sacrifice==col)||((own!=spell)&&lane==col))cell.getStyleClass().add("selected");
            row.getChildren().add(cell);
        }
        var box=new VBox(6,title,info,row);box.getStyleClass().add("board-section");return box;
    }
    private void select(MatchState.Card card) {
        if(!canAct())return;clearSelection();selected=card.instanceId();
        var d=Cards.get(card.id());
        if(d.cost()>view.players().get(view.viewer()).mana())message("冲动不足："+d.name()+"需要"+d.cost()+"点，当前"+view.players().get(view.viewer()).mana()+"点。");
        else message(d.name()+"："+(d.id().equals("grief")?"先点击己方牺牲牌，再点击敌方普通单位。":d.type()==CardDefinition.Type.SPELL?"请选择敌方普通单位。":"请选择己方空槽。"));
        preview.setText(d.name()+" · "+stats(d)+"\n"+d.text());render();
    }
    private void target(boolean own,int index) {
        if(!canAct()) {message(hidden?"请先接手查看手牌":"当前无法行动");return;}
        var c=selectedDefinition();if(c==null){message("请先选择一张手牌");return;}
        if(c.id().equals("grief")&&own) {sacrifice=index;lane=-1;previewId++;preview.clear();message("已选择牺牲列，请选择敌方普通单位");render();return;}
        if(c.type()==CardDefinition.Type.SPELL&&own || c.type()!=CardDefinition.Type.SPELL&&!own) {message("目标阵营错误，请按卡牌说明选择");return;}
        lane=index;render();requestPreview("play");
    }
    private CardDefinition selectedDefinition() {
        if(view==null)return null;
        return view.players().get(view.viewer()).hand().stream().filter(c->c.instanceId()==selected).map(c->Cards.get(c.id())).findFirst().orElse(null);
    }
    private boolean canAct(){return view!=null&&!view.over()&&view.viewer()==view.active()&&!hidden&&!pending&&!disconnected;}
    private void renderButtons(){confirm.setDisable(!canAct()||selected<0||lane<0);end.setDisable(!canAct());}
    private MatchState.Action command(String type){return new MatchState.Action(type,view.viewer(),selected,lane,sacrifice,view.version(),blocked.isSelected());}
    private Map<String,Object> request(String type) {
        var m=new LinkedHashMap<String,Object>();m.put("type",type);m.put("version",view.version());m.put("instanceId",selected);m.put("lane",lane);m.put("sacrifice",sacrifice);m.put("blocked",blocked.isSelected());return m;
    }
    private void requestPreview(String type) {
        if(!canAct()){message("当前无法预览，请先接手或等待自己的回合");return;} previewId++;
        try {
            if(local!=null) showPreview(local.preview(command(type)));
            else {var m=request("preview");m.put("action",type);m.put("requestId",previewId);send(m);}
        }catch(Exception e){preview.setText(e.getMessage());message(e.getMessage());confirm.setDisable(true);}
    }
    private void showPreview(GameEngine.Preview p) {
        preview.setText("若现在结束回合：天平 "+String.format("%+d",p.balance())+(p.over()?" · 对局结束":"")+"\n"+
                p.events().stream().map(MatchState.Event::text).collect(Collectors.joining("\n"))+"\n\n仅模拟当前选择；不预测对手行动。确认出牌不会结束回合。");
    }
    private void act(String type) {
        if(!canAct())return;
        try {
            if(local!=null) {
                int was=view.active();local.apply(command(type));clearSelection();blocked.setSelected(false);
                hidden=local.snapshot().active!=was&&!local.snapshot().over;refreshLocal();
                message(view.over()?"对局结束，可以导出记录。":hidden?"回合已切换，请交给下一位玩家。":"出牌成功，可以继续出牌或结束回合。");
            } else {pending=true;renderButtons();send(request(type));}
        } catch(Exception e){pending=false;message(e.getMessage());renderButtons();}
    }
    private void send(Object m){try{if(connection==null)throw new IllegalStateException("尚未连接服务器");connection.send(m);}catch(Exception e){pending=false;message(e.getMessage());renderButtons();}}
    private void clearSelection(){selected=-1;lane=-1;sacrifice=-1;previewId++;preview.clear();}
    private void export() {
        if(view==null){message("还没有可导出的对局");return;}
        var chooser=new FileChooser();chooser.setTitle("导出试玩记录");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON记录","*.json"));
        chooser.setInitialFileName("心理天平-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))+".json");
        var file=chooser.showSaveDialog(stage);if(file==null)return;exportDestination=file.toPath();
        if(local!=null) saveRecord(Json.GSON.toJsonTree(Records.full(local.snapshot(),notes.getText())));
        else send(Map.of("type","export","notes",notes.getText()));
    }
    private void saveRecord(JsonElement record){if(exportDestination==null)return;try{Files.writeString(exportDestination,Json.PRETTY.toJson(record),StandardCharsets.UTF_8);message("记录已保存："+exportDestination);}catch(Exception e){message("保存失败："+e.getMessage());}finally{exportDestination=null;}}
    private void disconnect(){closeConnection();pending=false;disconnected=true;message("已断开连接；重新连接后请加入或创建新局");render();}
    private void closeConnection(){generation++;if(connection!=null)connection.close();connection=null;}
    @Override public void stop(){closeConnection();}
    private void help(){
        var text=new TextArea("【目标】努斯向+12、逻各斯向-12推动公共天平。每次变化立即判胜；双方各完成30回合则按正负判胜，0平局。\n\n【出牌】三列共享逻辑编号，双方视角不会翻转左右。单位/资源放在己方空槽，不能移动或覆盖。确认出牌只出牌，结束回合才战斗。\n\n【战斗】左→中→右。对位单位同时伤害，致死仍反击；残血永久保留，无溢出伤害。新单位可交战，但到下一个自己的回合才能攻击空列推动天平。\n\n【冲动】每个己方回合基础+1，上限10。上回合余额最多保留2。按基础→储备→资源待用→亡语待用加入，总量最多10；溢出作废。\n\n【卡牌】焦虑2费：敌方普通单位攻击-2至其下个己方回合战斗结束，最低0，重复刷新不叠加。哀悼2费：牺牲己方在场牌，伤害敌方普通单位3点。\n\n【资源】后手首次正常抽牌后额外获得空虚/祈盼。0费，占槽且不攻击；己方回合末战斗后正常离场，下一己方回合+1。提前牺牲不给资源。\n\n【G/F】G用2费1/3余悸/释然替换烦躁/清明。战斗、法术或牺牲死亡后，下个己方回合+1；不当场退费。F保留原单位。‘不含法术’仍有资源及G亡语。\n\n【牌区】每方20张固定牌组，起手3，首回合也正常抽牌。手牌上限10，多抽直接弃牌；抽空不疲劳。双方牌库和手牌全空，自动完成剩余回合。\n\n【隐私与记录】联机只显示自己的手牌；种子由服务端保管。进行中导出仅含本人视角；终局可导出完整v3记录。不支持旧HTML记录导入。断线结束本局，可回房重新准备。\n\n【预览】先选手牌及目标再看预览，哀悼先选己方牺牲再选敌方。预览假设立即结束回合，不会改变真实状态或显示下一张牌。\n\n【素材】当前使用文字卡面；可添加resources/cards/<稳定卡牌ID>.png替换。原型数值用于复现，不代表最终平衡。 ");
        text.setEditable(false);text.setWrapText(true);text.setPrefSize(650,580);var dialog=new Dialog<Void>();dialog.setTitle("心理天平 · 规则与操作");dialog.initOwner(stage);dialog.getDialogPane().setContent(text);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);dialog.show();
    }
    private void message(String s){status.setText(s==null?"操作失败":s);}
    private static String stats(CardDefinition c){return switch(c.type()){case UNIT->c.attack()+"攻 / "+c.health()+"生命";case SPELL->"法术 · 需选目标";case RESOURCE->"资源 · 需空槽";};}
    private static Label title(String text,int size){var l=new Label(text);l.setStyle("-fx-font-size:"+size+"px; -fx-font-weight:bold;");l.setWrapText(true);return l;}
    private static Label label(String s){var l=new Label(s);l.setWrapText(true);return l;}
    private static Region spacer(){var r=new Region();HBox.setHgrow(r,Priority.ALWAYS);return r;}
    private static TextField field(TextField f,int width){f.setPrefWidth(width);return f;}
    private static Button button(String text,String id,Runnable action){var b=new Button(text);b.setId(id);b.setOnAction(e->action.run());return b;}
}
