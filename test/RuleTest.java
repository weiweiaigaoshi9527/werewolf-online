import com.werewolf.rules.*;

import java.util.*;

/** 针对本轮「原版规则修复」的定向验证。 */
public class RuleTest {

    static int pass = 0, fail = 0;

    static void ok(boolean c, String msg) {
        if (c) { pass++; System.out.println("  PASS  " + msg); }
        else { fail++; System.out.println("  FAIL  " + msg); }
    }

    public static void main(String[] args) {
        testWitchNoSelfPoison();
        testWolfKingShootChain();
        testNormalWolfBlowUp();
        testWolfKillTieDeterministic();
        testBlowUpPhaseGuards();
        testSheriffTransferByOwner();
        testSheriffTransferOnNightKill();
        testSheriffOrderByOwner();
        System.out.println();
        System.out.println("PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    /** A. 女巫不能毒自己 */
    static void testWitchNoSelfPoison() {
        System.out.println("[A] 女巫不能毒自己");
        List<Role> roles = Arrays.asList(Role.WEREWOLF, Role.WEREWOLF, Role.SEER, Role.WITCH,
                Role.VILLAGER, Role.VILLAGER);
        GameEngine g = new GameEngine(roles, true);
        ok(g.phase() == GamePhase.NIGHT_WOLF, "起始阶段 NIGHT_WOLF, 实际=" + g.phase());
        g.submitWolfKill(1, 5);
        g.submitWolfKill(2, 5);
        ok(g.phase() == GamePhase.NIGHT_WITCH, "进入 NIGHT_WITCH, 实际=" + g.phase());
        boolean threw = false;
        try { g.submitWitch(0, 4); } catch (Exception e) { threw = true; System.out.println("      拒绝: " + e.getMessage()); }
        ok(threw, "毒自己应被拒绝");
        ok(g.phase() == GamePhase.NIGHT_WITCH, "被拒后仍停在 NIGHT_WITCH, 实际=" + g.phase());
        g.submitWitch(0, 0);
        ok(g.phase() == GamePhase.NIGHT_SEER, "正常跳过后进入 NIGHT_SEER, 实际=" + g.phase());
    }

    /** B. 狼王被猎人枪杀后可以开枪（连环枪） */
    static void testWolfKingShootChain() {
        System.out.println("[B] 狼王被枪杀可开枪（连环枪）");
        List<Role> roles = Arrays.asList(Role.WOLF_KING, Role.WEREWOLF, Role.HUNTER, Role.VILLAGER,
                Role.VILLAGER, Role.SEER);
        GameEngine g = new GameEngine(roles, true);
        g.submitWolfKill(1, 3);
        g.submitWolfKill(2, 3);
        ok(g.phase() == GamePhase.NIGHT_SEER, "进入 NIGHT_SEER, 实际=" + g.phase());
        g.submitSeer(1);
        ok(g.phase() == GamePhase.SHOOT, "猎人死亡进入 SHOOT, 实际=" + g.phase());
        ok(g.currentActor() == 3, "开枪者为 3 号猎人, 实际=" + g.currentActor());
        g.submitShoot(3, 1);
        ok(!g.bySeat(1).alive, "狼王 1 号被带走出局");
        ok(g.bySeat(1).canShootOnDeath, "狼王 canShootOnDeath=true");
        ok(g.phase() == GamePhase.SHOOT && g.currentActor() == 1,
                "轮到狼王 1 号开枪, 实际 phase=" + g.phase() + " actor=" + g.currentActor());
        g.submitShoot(1, 4);
        ok(!g.bySeat(4).alive, "狼王开枪带走 4 号");
    }

    /** C. 普通狼人自爆：直接入夜、不带人 */
    static void testNormalWolfBlowUp() {
        System.out.println("[C] 普通狼人自爆");
        List<Role> roles = Arrays.asList(Role.WEREWOLF, Role.WEREWOLF, Role.SEER, Role.VILLAGER,
                Role.VILLAGER, Role.VILLAGER);
        GameEngine g = new GameEngine(roles, true);
        g.submitWolfKill(1, 5);
        g.submitWolfKill(2, 5);
        g.submitSeer(1);
        int guard = 0;
        while (g.phase() != GamePhase.DAY_SPEAK && !g.isGameOver() && guard++ < 100) {
            int actor = g.currentActor();
            if (actor == 0) { if (!g.resolveStuckPhase()) break; continue; }
            switch (g.currentActionKind()) {
                case "SHOOT" -> g.submitShoot(actor, 0);
                case "LAST_WORDS" -> g.submitLastWords(actor, "遗言");
                case "SHERIFF_SIGNUP" -> g.submitSheriffSignup(actor, false);
                default -> { guard = 999; }
            }
        }
        ok(g.phase() == GamePhase.DAY_SPEAK, "已推进到 DAY_SPEAK, 实际=" + g.phase());
        ok(g.canBlowUp(1), "普通狼人 1 号可自爆");
        ok(!g.canBlowUp(4), "村民 4 号不可自爆");
        boolean threw = false;
        try { g.submitWolfBlowUp(2, 4); } catch (Exception e) { threw = true; }
        ok(threw, "普通狼人自爆带人应被拒绝");
        ok(g.phase() == GamePhase.DAY_SPEAK, "被拒后仍在 DAY_SPEAK");
        g.submitWolfBlowUp(1, 0);
        ok(!g.bySeat(1).alive, "自爆后 1 号出局");
        ok("BLOWUP".equals(g.bySeat(1).deathCause), "死因为 BLOWUP");
        ok(g.phase() != GamePhase.DAY_SPEAK, "自爆后离开白天（直接入夜）, 实际=" + g.phase());
        ok(g.bySeat(2).alive, "狼队友 2 号不受自爆影响");
    }

    /** D. 狼刀平票结果可复现 */
    static void testWolfKillTieDeterministic() {
        System.out.println("[D] 狼刀平票结果确定（可复现）");
        Integer first = null;
        for (int i = 0; i < 3; i++) {
            List<Role> roles = Arrays.asList(Role.WEREWOLF, Role.WEREWOLF, Role.VILLAGER,
                    Role.VILLAGER, Role.VILLAGER, Role.VILLAGER);
            GameEngine g = new GameEngine(roles, true);
            g.submitWolfKill(1, 3);
            g.submitWolfKill(2, 4);
            int kill = g.nightWolfKill();
            if (first == null) { first = kill; ok(kill == 3 || kill == 4, "平票结果落在候选集合内: " + kill); }
            else ok(first == kill, "第 " + (i + 1) + " 次复现结果一致: " + kill);
        }
    }

    /** E. 自爆只在白天、且白狼王必须带人 */
    static void testBlowUpPhaseGuards() {
        System.out.println("[E] 自爆阶段与白狼王规则");
        List<Role> roles = Arrays.asList(Role.WHITE_WOLF_KING, Role.WEREWOLF, Role.SEER,
                Role.VILLAGER, Role.VILLAGER, Role.VILLAGER);
        GameEngine g = new GameEngine(roles, true);
        ok(!g.canBlowUp(1), "夜晚不可自爆（canBlowUp=false）");
        boolean threw = false;
        try { g.submitWolfBlowUp(1, 0); } catch (Exception e) { threw = true; }
        ok(threw, "夜晚调用自爆应被拒绝");
        g.submitWolfKill(1, 4);
        g.submitWolfKill(2, 4);
        g.submitSeer(1);
        int guard = 0;
        while (g.phase() != GamePhase.DAY_SPEAK && !g.isGameOver() && guard++ < 100) {
            int actor = g.currentActor();
            if (actor == 0) { if (!g.resolveStuckPhase()) break; continue; }
            switch (g.currentActionKind()) {
                case "SHOOT" -> g.submitShoot(actor, 0);
                case "LAST_WORDS" -> g.submitLastWords(actor, "遗言");
                case "SHERIFF_SIGNUP" -> g.submitSheriffSignup(actor, false);
                default -> { guard = 999; }
            }
        }
        ok(g.canBlowUp(1), "白天白狼王可自爆");
        boolean needTarget = false;
        try { g.submitWolfBlowUp(1, 0); } catch (Exception e) { needTarget = true; System.out.println("      拒绝: " + e.getMessage()); }
        ok(needTarget, "白狼王自爆不带人应被拒绝");
        g.submitWolfBlowUp(1, 3);
        ok(!g.bySeat(1).alive && !g.bySeat(3).alive, "白狼王自爆带走 3 号");
        ok(!g.canBlowUp(1), "白狼王已自爆后 1 号不可再自爆");
    }

    /* ================= 警徽移交 ================= */

    static int transferSeen = 0, transferAsserted = 0;

    /**
     * F. 警长死亡后由警长本人移交警徽：
     * 跑多局随机对局，只要出现 SHERIFF_TRANSFER 阶段就校验其不变量，并由「警长本人」决定去向。
     */
    static void testSheriffTransferByOwner() {
        System.out.println("[F] 警长死亡后由本人移交警徽（随机对局采样）");
        int total = 0;
        for (long seed = 1; seed <= 24; seed++) total += randomGame(seed);
        ok(total > 0, "随机对局中至少触发过一次警徽移交（共 " + total + " 次）");
        ok(transferAsserted > 0, "移交阶段的断言已执行（" + transferAsserted + " 组）");
    }

    /** 跑一局随机对局，返回遇到的「警徽移交」次数；对每次移交校验不变量并执行移交。 */
    static int randomGame(long seed) {
        List<Role> roles = Arrays.asList(
                Role.WEREWOLF, Role.WEREWOLF, Role.WEREWOLF, Role.WEREWOLF,
                Role.VILLAGER, Role.VILLAGER, Role.VILLAGER, Role.VILLAGER,
                Role.SEER, Role.WITCH, Role.HUNTER, Role.IDIOT);
        GameEngine g = new GameEngine(roles, true);
        Random rnd = new Random(seed * 7919L);
        int transfers = 0, fails = 0;
        for (int i = 0; i < 1200 && !g.isGameOver(); i++) {
            int actor = g.currentActor();
            if (actor == 0) { if (!g.resolveStuckPhase()) break; continue; }
            String kind = g.currentActionKind();
            try {
                if ("SHERIFF_TRANSFER".equals(kind)) {
                    transfers++;
                    transferSeen++;
                    int from = actor;
                    if (transferAsserted < 6) {
                        transferAsserted++;
                        ok(g.pendingSheriffTransfer() == from, "移交阶段行动者 = 待移交警长(" + from + "号)");
                        ok(g.bySeat(from) != null && !g.bySeat(from).alive, "移交警徽者已出局");
                        ok(g.sheriffSeat() == 0, "移交前警徽已收回（sheriffSeat=0）");
                    }
                    List<Integer> cand = new ArrayList<>();
                    for (Player p : g.alive()) if (p.seat != from) cand.add(p.seat);
                    int tgt = cand.isEmpty() ? 0 : cand.get(rnd.nextInt(cand.size()));
                    g.submitSheriffTransfer(tgt);
                    if (transferAsserted <= 6) {
                        if (tgt != 0) ok(g.sheriffSeat() == tgt && g.bySeat(tgt).isSheriff, "警徽成功移交给 " + tgt + " 号");
                        else ok(g.sheriffSeat() == 0, "警徽成功撕毁");
                    }
                } else {
                    act(g, actor, kind, rnd);
                }
                fails = 0;
            } catch (Exception e) {
                if (++fails > 80) break;
                g.resolveStuckPhase();
            }
        }
        return transfers;
    }

    static void act(GameEngine g, int actor, String kind, Random rnd) {
        List<Integer> alive = g.alive().stream().map(p -> p.seat).toList();
        List<Integer> good = g.aliveGood().stream().map(p -> p.seat).toList();
        switch (kind) {
            case "GUARD" -> g.submitGuard(rnd.nextInt(4) == 0 ? 0 : pickOther(alive, actor, rnd));
            case "WOLF_KILL" -> g.submitWolfKill(actor,
                    (good.isEmpty() || rnd.nextInt(10) == 0) ? 0 : good.get(rnd.nextInt(good.size())));
            case "WITCH" -> g.submitWitch(0, 0);
            case "SEER_CHECK" -> g.submitSeer(pickOther(alive, actor, rnd));
            case "CROW" -> g.submitCrow(rnd.nextInt(5) == 0 ? 0 : pickOther(alive, actor, rnd));
            case "SILENCER" -> g.submitSilencer(rnd.nextInt(5) == 0 ? 0 : pickOther(alive, actor, rnd));
            case "SHOOT" -> g.submitShoot(actor, rnd.nextInt(3) == 0 ? 0 : pickOther(alive, actor, rnd));
            case "LAST_WORDS" -> g.submitLastWords(actor, "遗言");
            case "SHERIFF_SIGNUP" -> g.submitSheriffSignup(actor, rnd.nextBoolean());
            case "SHERIFF_WITHDRAW" -> g.submitSheriffWithdraw(actor, rnd.nextBoolean());
            case "SHERIFF_VOTE" -> {
                List<Integer> c = new ArrayList<>(g.sheriffCandidates());
                c.remove((Integer) actor);
                g.submitSheriffVote(actor, c.isEmpty() ? 0 : c.get(rnd.nextInt(c.size())));
            }
            case "SPEECH" -> g.submitSpeech(actor, "发言");
            case "VOTE" -> g.submitVote(actor, pickOther(alive, actor, rnd));
            case "PK_SPEECH" -> g.submitTiebreakSpeech(actor, "pk");
            case "PK_VOTE" -> {
                List<Integer> c = new ArrayList<>(g.tiebreakCandidates());
                c.remove((Integer) actor);
                g.submitTiebreakVote(actor, c.isEmpty() ? 0 : c.get(rnd.nextInt(c.size())));
            }
            case "SHERIFF_ORDER" -> {
                List<Integer> seats = g.alive().stream().map(p -> p.seat).sorted().toList();
                g.submitSheriffOrder(seats.isEmpty() ? 0 : seats.get(rnd.nextInt(seats.size())));
            }
            default -> { }
        }
    }

    static int pickOther(List<Integer> seats, int self, Random rnd) {
        List<Integer> c = new ArrayList<>(seats);
        c.remove((Integer) self);
        return c.isEmpty() ? 0 : c.get(rnd.nextInt(c.size()));
    }

    static byte[] ser(Object o) {
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream os = new java.io.ObjectOutputStream(b)) { os.writeObject(o); }
            return b.toByteArray();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static Object deser(byte[] d) {
        try (java.io.ObjectInputStream is = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(d))) {
            return is.readObject();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** G. 确定性用例：警长夜里被刀 → 由警长本人决定警徽去向。 */
    static void testSheriffTransferOnNightKill() {
        System.out.println("[G] 警长夜里被刀 → 本人移交警徽（确定性用例）");
        List<Role> roles = Arrays.asList(Role.WEREWOLF, Role.WEREWOLF, Role.SEER, Role.VILLAGER,
                Role.VILLAGER, Role.VILLAGER, Role.VILLAGER, Role.VILLAGER);
        GameEngine g = new GameEngine(roles, true);
        // 夜1：狼刀 8 号；预言家验人
        g.submitWolfKill(1, 8);
        g.submitWolfKill(2, 8);
        g.submitSeer(1);
        // 推进到放逐投票（4 号单独上警 → 当选警长）
        runUntil(g, GamePhase.DAY_VOTE, 8, 7);
        ok(g.sheriffSeat() == 4, "4 号当选警长, 实际=" + g.sheriffSeat());
        // 第 1 天放逐 7 号，随后进入第 2 夜
        runUntil(g, GamePhase.NIGHT_WOLF, 8, 7);
        ok(g.day() == 2, "已进入第 2 天夜晚, 实际 day=" + g.day());
        // 夜2：狼刀警长 4 号
        g.submitWolfKill(1, 4);
        g.submitWolfKill(2, 4);
        g.submitSeer(1);
        ok(g.phase() == GamePhase.SHERIFF_TRANSFER, "警长死亡后进入警徽移交阶段, 实际=" + g.phase());
        ok(g.currentActor() == 4, "由已出局的警长 4 号本人决定, 实际=" + g.currentActor());
        ok("SHERIFF_TRANSFER".equals(g.currentActionKind()), "actionKind = SHERIFF_TRANSFER");
        ok(g.sheriffSeat() == 0, "移交前警徽已收回");
        // 快照恢复：待移交状态必须能被序列化恢复（服务端重启后可继续）
        GameEngine g2 = (GameEngine) deser(ser(g));
        ok(g2.phase() == GamePhase.SHERIFF_TRANSFER, "序列化恢复后仍在警徽移交阶段");
        ok(g2.pendingSheriffTransfer() == 4, "恢复后待移交警长仍为 4 号");
        ok(g2.currentActor() == 4, "恢复后行动者仍为 4 号");
        ok(g2.sheriffSeat() == 0, "恢复后警徽仍处于收回状态");
        boolean bad = false;
        try { g.submitSheriffTransfer(4); } catch (Exception e) { bad = true; }
        ok(bad, "不能把警徽交给自己（已出局）");
        g.submitSheriffTransfer(5);
        ok(g.sheriffSeat() == 5 && g.bySeat(5).isSheriff, "警徽成功移交给 5 号");
        ok(g.phase() != GamePhase.SHERIFF_TRANSFER, "移交后离开该阶段, 实际=" + g.phase());
        ok(g.day() == 2, "移交后仍在第 2 天, 实际=" + g.day());
    }

    /** H. 警长决定白天发言顺序（确定性用例）。 */
    static void testSheriffOrderByOwner() {
        System.out.println("[H] 警长决定发言顺序（确定性用例）");
        List<Role> roles = Arrays.asList(Role.WEREWOLF, Role.WEREWOLF, Role.SEER, Role.VILLAGER,
                Role.VILLAGER, Role.VILLAGER, Role.VILLAGER, Role.VILLAGER);
        GameEngine g = new GameEngine(roles, true);
        g.submitWolfKill(1, 8);
        g.submitWolfKill(2, 8);
        g.submitSeer(1);
        // 推进到「决定发言顺序」阶段（4 号单独上警 → 当选警长）
        runUntil(g, GamePhase.SHERIFF_ORDER, 8, 7);
        ok(g.phase() == GamePhase.SHERIFF_ORDER, "进入决定发言顺序阶段, 实际=" + g.phase());
        ok(g.sheriffSeat() == 4, "4 号当选警长, 实际=" + g.sheriffSeat());
        ok(g.currentActor() == 4, "由警长 4 号本人决定, 实际=" + g.currentActor());
        ok("SHERIFF_ORDER".equals(g.currentActionKind()), "actionKind = SHERIFF_ORDER");
        ok(g.speakingOrder().isEmpty(), "尚未生成发言顺序");
        boolean bad = false;
        try { g.submitSheriffOrder(8); } catch (Exception e) { bad = true; }   // 8 号已出局
        ok(bad, "起始座位必须是存活玩家（8 号已出局）");
        ok(g.phase() == GamePhase.SHERIFF_ORDER, "被拒后仍在决定发言顺序阶段");
        // 从警右（5 号）开始：存活为 1,2,3,4,5,6,7
        g.submitSheriffOrder(5);
        ok(g.phase() == GamePhase.DAY_SPEAK, "进入白天发言, 实际=" + g.phase());
        ok(g.speakingOrder().toString().equals("[5, 6, 7, 1, 2, 3, 4]"), "发言顺序正确: " + g.speakingOrder());
        ok(g.currentSpeaker() == 5, "首位发言者 = 5 号, 实际=" + g.currentSpeaker());
        // 快照恢复：发言顺序应被序列化保留
        GameEngine g2 = (GameEngine) deser(ser(g));
        ok(g2.speakingOrder().toString().equals("[5, 6, 7, 1, 2, 3, 4]"), "恢复后发言顺序一致");
        ok(g2.currentSpeaker() == 5, "恢复后首位发言者仍为 5 号");
    }

    /** 按固定脚本推进到目标阶段（用于确定性用例）。 */
    static void runUntil(GameEngine g, GamePhase stop, int wolfTarget, int exileTarget) {
        for (int i = 0; i < 400 && g.phase() != stop && !g.isGameOver(); i++) {
            int actor = g.currentActor();
            if (actor == 0) { if (!g.resolveStuckPhase()) return; continue; }
            try {
                switch (g.currentActionKind()) {
                    case "WOLF_KILL" -> g.submitWolfKill(actor, wolfTarget);
                    case "SEER_CHECK" -> g.submitSeer(pickOther(
                            g.alive().stream().map(p -> p.seat).toList(), actor, new Random(7)));
                    case "SHOOT" -> g.submitShoot(actor, 0);
                    case "LAST_WORDS" -> g.submitLastWords(actor, "遗言");
                    case "SHERIFF_SIGNUP" -> g.submitSheriffSignup(actor, actor == 4);
                    case "SHERIFF_WITHDRAW" -> g.submitSheriffWithdraw(actor, false);
                    case "SHERIFF_VOTE" -> {
                        int c = 0;
                        for (int s : g.sheriffCandidates()) if (s != actor) { c = s; break; }
                        g.submitSheriffVote(actor, c);
                    }
                    case "SPEECH" -> g.submitSpeech(actor, "发言");
                    case "VOTE" -> g.submitVote(actor, actor == exileTarget ? 0 : exileTarget);
                    case "PK_SPEECH" -> g.submitTiebreakSpeech(actor, "pk");
                    case "PK_VOTE" -> g.submitTiebreakVote(actor, actor == exileTarget ? 0 : exileTarget);
                    case "SHERIFF_ORDER" -> {
                        // 确定性脚本：默认从警右（座位号更大的下一位）开始
                        List<Integer> seats = g.alive().stream().map(p -> p.seat).sorted().toList();
                        int idx = seats.indexOf(g.sheriffSeat());
                        g.submitSheriffOrder(seats.isEmpty() ? 0 : seats.get(idx < 0 ? 0 : (idx + 1) % seats.size()));
                    }
                    default -> { return; }
                }
            } catch (Exception e) {
                g.resolveStuckPhase();
            }
        }
    }
}
