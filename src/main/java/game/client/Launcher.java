package game.client;

/** 独立入口使 classpath 运行 JavaFX 时不触发 JVM 的模块启动特殊处理。 */
public final class Launcher {
    public static void main(String[] args) { javafx.application.Application.launch(GameApp.class,args); }
}
