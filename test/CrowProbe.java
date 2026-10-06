import com.werewolf.rules.*;
import java.util.List;

public class CrowProbe {
    public static void main(String[] a) {
        // 板子：狼+乌鸦+预女+民×3
        GameEngine g = new GameEngine(List.of(
                Role.WEREWOLF, Role.CROW, Role.SEER, Role.WITCH, Role.VILLAGER, Role.VILLAGER), true);
        System.out.println("phase=" + g.phase());
        // 狼空刀
        while (g.phase().isNight()) {
            switch (g.phase()) {
                case NIGHT_GUARD -> g.submitGuard(0);
                case NIGHT_WOLF -> g.submitWolfKill(g.nextWolfToAct(), 0);
                case NIGHT_WITCH -> g.submitWitch(0, 0);
                case NIGHT_SEER -> g.submitSeer(3);
                case NIGHT_CROW -> {
                    System.out.println(">>> 乌鸦回合，尝试污蔑 1 号（狼）");
                    g.submitCrow(1);
                    System.out.println(">>> submitCrow 成功");
                }
                default -> { return; }
            }
        }
        Player crow = g.bySeat(2);
        Player wolf = g.bySeat(1);
        System.out.println("wolf.accusedByCrow=" + wolf.accusedByCrow);
        System.out.println("crow alive=" + crow.alive + " phase=" + g.phase());
        // 推进到投票：看 +1 是否计入（简单验证 tally 由引擎内部处理，这里验证 accused 状态与事件）
        boolean evt = g.events().stream().anyMatch(e -> "NIGHT_ACTION".equals(e.type()) && e.actorSeat() == 2 && e.targetSeat() == 1 && e.detail().contains("CROW_ACCUSE"));
        System.out.println("CROW_ACCUSE event=" + evt);
    }
}
