package game.net;

import game.core.*;
import java.time.Instant;
import java.util.*;

public final class Records {
    private Records() {}
    public static Map<String,Object> full(MatchState state,String notes) {
        return Map.of("format","心理天平Java试玩记录","version",3,"balanceVersion","prototype-build07",
                "exported",Instant.now().toString(),"notes",notes,"cardDefinitions",Cards.all(),"game",state);
    }
    public static Map<String,Object> visible(GameView view,String notes) {
        return Map.of("format","心理天平Java视角记录","version",3,"complete",false,
                "exported",Instant.now().toString(),"notes",notes,"view",view);
    }
}
