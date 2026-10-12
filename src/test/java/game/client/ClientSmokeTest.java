package game.client;

import game.core.Cards;
import game.server.GameServer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** 需要可用桌面的真实 JavaFX 集成测试；普通构建不强制有显示器。 */
@EnabledIfSystemProperty(named="uiTests",matches="true")
class ClientSmokeTest {
    static void fx(Runnable work) throws Exception { var task=new FutureTask<Void>(()->{work.run();return null;});Platform.runLater(task);task.get(10,TimeUnit.SECONDS); }
    static <T> T fxCall(Callable<T> work) throws Exception {var task=new FutureTask<>(work);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
    static void until(BooleanSupplier condition) throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!fxCall(condition::getAsBoolean)){if(System.nanoTime()>deadline)fail("UI等待超时");Thread.sleep(40);}}
    static Button button(Stage stage,String id){return (Button)stage.getScene().lookup("#"+id);}
    static TextField field(Stage stage,String id){return (TextField)stage.getScene().lookup("#"+id);}
    static String status(Stage stage){return ((Label)stage.getScene().lookup("#status")).getText();}
    @BeforeAll static void startFx() throws Exception {var latch=new CountDownLatch(1);Platform.startup(()->{Platform.setImplicitExit(false);latch.countDown();});assertTrue(latch.await(10,TimeUnit.SECONDS));}
    @Test void localSelectionPreviewConfirmationHandoffAndHelp() throws Exception {
        var app=new GameApp();var holder=new Stage[1];
        try {
            fx(()->{holder[0]=new Stage();app.start(holder[0]);});var stage=holder[0];
            String seed=null;
            for(int i=0;i<100;i++){String candidate="ui-"+i;if(Cards.deck(0,"spells",candidate,"G").subList(0,4).stream().anyMatch(id->Cards.get(id).cost()==1)){seed=candidate;break;}}
            String chosenSeed=seed;assertNotNull(chosenSeed);
            fx(()->{field(stage,"seed").setText(chosenSeed);button(stage,"local-start").fire();assertNotNull(button(stage,"reveal"));assertNull(stage.getScene().lookup("#hand"));button(stage,"reveal").fire();});
            long instance=Cards.deck(0,"spells",chosenSeed,"G").subList(0,4).stream().filter(id->Cards.get(id).cost()==1).mapToLong(id->Cards.deck(0,"spells",chosenSeed,"G").indexOf(id)+1).findFirst().orElseThrow();
            fx(()->{
                int before=((FlowPane)stage.getScene().lookup("#hand")).getChildren().size();assertEquals(4,before);
                button(stage,"hand-"+instance).fire();button(stage,"board-0-0").fire();
                assertTrue(((TextArea)stage.getScene().lookup("#preview")).getText().contains("若现在结束回合"));
                assertEquals(before,((FlowPane)stage.getScene().lookup("#hand")).getChildren().size());
                button(stage,"confirm").fire();assertEquals(3,((FlowPane)stage.getScene().lookup("#hand")).getChildren().size());
                assertTrue(button(stage,"board-0-0").getText().contains("当前生命"));
                button(stage,"end").fire();assertNotNull(button(stage,"reveal"));assertTrue(button(stage,"end").isDisabled());
                button(stage,"reveal").fire();assertEquals(5,((FlowPane)stage.getScene().lookup("#hand")).getChildren().size());
                button(stage,"help").fire();assertTrue(Stage.getWindows().size()>1);
            });
        } finally {fx(()->{app.stop();for(var w:java.util.List.copyOf(Stage.getWindows()))w.hide();});}
    }
    @Test void twoJavaFxWindowsCompleteNetworkGameAndRematch() throws Exception {
        try(var server=new GameServer("127.0.0.1",0)) {
            server.start();var apps=new GameApp[]{new GameApp(),new GameApp()};var stages=new Stage[2];
            try {
                fx(()->{for(int i=0;i<2;i++){stages[i]=new Stage();apps[i].start(stages[i]);field(stages[i],"port").setText(""+server.port());field(stages[i],"nickname").setText("UI玩家"+i);}button(stages[0],"create").fire();});
                until(()->field(stages[0],"room-code").getText().length()==6);
                fx(()->{field(stages[1],"room-code").setText(field(stages[0],"room-code").getText());button(stages[1],"join").fire();});
                until(()->status(stages[0]).contains("UI玩家1")&&status(stages[1]).contains("UI玩家0"));
                fx(()->{button(stages[0],"ready").fire();button(stages[1],"ready").fire();});
                until(()->stages[0].getScene().lookup("#hand")!=null&&stages[1].getScene().lookup("#hand")!=null);
                fx(()->{assertEquals(4,((FlowPane)stages[0].getScene().lookup("#hand")).getChildren().size());assertEquals(3,((FlowPane)stages[1].getScene().lookup("#hand")).getChildren().size());assertTrue(button(stages[1],"end").isDisabled());});
                for(int turn=0;turn<60;turn++) {
                    int f=turn%2;until(()->!button(stages[f],"end").isDisabled());fx(()->button(stages[f],"end").fire());
                }
                until(()->!button(stages[0],"ready").isDisabled()&&!button(stages[1],"ready").isDisabled());
                fx(()->{assertTrue(((TextArea)stages[0].getScene().lookup("#battle-log")).getText().contains("双方各完成30回合"));button(stages[0],"ready").fire();button(stages[1],"ready").fire();});
                until(()->!button(stages[0],"end").isDisabled());
                fx(()->{assertEquals(4,((FlowPane)stages[0].getScene().lookup("#hand")).getChildren().size());button(stages[1],"disconnect").fire();});
                until(()->((TextArea)stages[0].getScene().lookup("#battle-log")).getText().contains("断线"));
            } finally {fx(()->{for(var app:apps)app.stop();for(var stage:stages)if(stage!=null)stage.close();});}
        }
    }
}
