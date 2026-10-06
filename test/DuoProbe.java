import com.werewolf.rules.*;
import java.util.List;

public class DuoProbe {
    public static void main(String[] a) {
        // 9 人标准局：3 狼 3 民 预女猎，组队对子 (1,2) 和 (5,6)
        BoardConfig b = BoardConfig.preset9();
        for (int seed = 1; seed <= 5; seed++) {
            GameEngine g = new GameEngine(b, List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L), seed, true,
                    List.of(new int[]{1, 2}, new int[]{5, 6}));
            Player p1 = g.bySeat(1), p2 = g.bySeat(2), p5 = g.bySeat(5), p6 = g.bySeat(6);
            boolean ok12 = p1.isWolf() == p2.isWolf();
            boolean ok56 = p5.isWolf() == p6.isWolf();
            long wolves = g.players().stream().filter(Player::isWolf).count();
            System.out.println("seed=" + seed
                    + " 1号=" + p1.role.cnName() + " 2号=" + p2.role.cnName()
                    + " | 5号=" + p5.role.cnName() + " 6号=" + p6.role.cnName()
                    + " | 同阵营: 12=" + ok12 + " 56=" + ok56
                    + " | 狼总数=" + wolves + "(期望3)");
            if (!ok12 || !ok56 || wolves != 3) { System.out.println("FAIL"); System.exit(1); }
        }
        System.out.println("DUO_DEAL_ALL_OK");
    }
}
