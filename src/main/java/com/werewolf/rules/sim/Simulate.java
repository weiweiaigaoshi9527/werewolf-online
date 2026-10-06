package com.werewolf.rules.sim;

import com.werewolf.rules.*;

import java.util.*;

/**
 * M2 规则引擎验证主程序。
 * 1) 200 局随机板子自动对局：断言零死锁、零非法状态、每局都能正常分出胜负；
 * 2) 7 个技能定点用例：同守同救、守护成功、女巫救人、猎人被刀连锁开枪、白痴翻牌、猎人被毒不开枪、狼王被放逐开枪。
 * 运行：java ... com.werewolf.rules.sim.Simulate，退出码非 0 表示有失败。
 */
public class Simulate {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("==== M2 规则引擎验证 ====\n");
        runRandomGames(200);
        System.out.println("\n---- 技能定点用例 ----");
        testGuardWitchSameTarget();
        testGuardProtect();
        testWitchSave();
        testHunterShootOnNightDeath();
        testIdiotReveal();
        testHunterPoisonedNoShoot();
        testWolfKingShootOnExile();
        testGoodWinByExilingLastWolf();
        testItemShield();
        testItemReveal();
        testItemSilence();
        testItemReviveNight();
        testItemRevivePoison();
        testItemReviveExile();
        testItemIron();

        System.out.printf("%n==== 结果：通过 %d，失败 %d ====%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    /* ================= 随机对局 ================= */

    static void runRandomGames(int n) {
        System.out.println("---- 随机对局 " + n + " 局 ----");
        long base = 20261001L;
        int wolfWin = 0, goodWin = 0, fail = 0;
        int maxDays = 0; long totalSteps = 0;
        Map<String, Integer> boardShapes = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Random rnd = new Random(base + i);
            BoardConfig board = randomBoard(rnd);
            int total = board.total();
            List<Long> ids = new ArrayList<>();
            for (int s = 1; s <= total; s++) ids.add((long) s);
            boolean border = rnd.nextBoolean();
            GameEngine g = new GameEngine(board, ids, base + i * 7L, border);
            SimulationDriver.Result r = SimulationDriver.run(g, new RandomPolicy(), base + i * 13L);
            if (!r.ok()) {
                fail++;
                System.out.println("[FAIL] 局#" + i + " board=" + board + " border=" + border + " -> " + r.error());
                // 打印最后 15 条事件辅助定位
                List<GameEvent> ev = r.events();
                for (int k = Math.max(0, ev.size() - 15); k < ev.size(); k++) System.out.println("   " + ev.get(k));
                continue;
            }
            if (r.winner() == Faction.WOLF) wolfWin++; else goodWin++;
            maxDays = Math.max(maxDays, r.days());
            totalSteps += r.steps();
            boardShapes.merge(board.toString(), 1, Integer::sum);
        }
        check(fail == 0, "随机对局零失败", "失败 " + fail + " 局");
        System.out.printf("  狼人胜 %d 局，好人胜 %d 局，最长 %d 天，平均步数 %d%n",
                wolfWin, goodWin, maxDays, n > 0 ? totalSteps / n : 0);
        // 断言双方都有获胜（策略随机但不应出现某方 100% 胜，除非规则 bug）
        check(wolfWin > 0 && goodWin > 0, "胜负分布合理（双方均有胜局）", "wolf=" + wolfWin + " good=" + goodWin);
    }

    static BoardConfig randomBoard(Random rnd) {
        while (true) {
            int wolves = 1 + rnd.nextInt(4); // 1..4
            int total = 6 + rnd.nextInt(11); // 6..16
            int good = total - wolves;
            if (wolves >= good) continue;
            List<Role> godTypes = new ArrayList<>(List.of(
                    Role.SEER, Role.WITCH, Role.HUNTER, Role.GUARD, Role.IDIOT, Role.CROW, Role.SILENCER));
            Collections.shuffle(godTypes, rnd);
            int numGod = 1 + rnd.nextInt(Math.min(godTypes.size(), good - 1)); // >=1 god, >=1 villager
            int villagers = good - numGod;
            if (villagers < 1) continue;

            BoardConfig b = new BoardConfig();
            int regularWolves = wolves;
            // 有概率把一只狼升级为狼王/白狼王
            if (wolves >= 2 && rnd.nextInt(100) < 30) {
                Role king = rnd.nextBoolean() ? Role.WOLF_KING : Role.WHITE_WOLF_KING;
                b.put(king, 1);
                regularWolves = wolves - 1;
            }
            b.put(Role.WEREWOLF, regularWolves);
            for (int i = 0; i < numGod; i++) b.put(godTypes.get(i), 1);
            b.put(Role.VILLAGER, villagers);
            if (BoardValidator.isValid(b)) return b;
        }
    }

    /* ================= 技能定点用例 ================= */

    /** 同守同救同一被刀目标 → 目标死亡（奶穿）。 */
    static void testGuardWitchSameTarget() {
        GameEngine g = new GameEngine(List.of(
                Role.GUARD, Role.WEREWOLF, Role.WITCH, Role.VILLAGER, Role.SEER, Role.VILLAGER), true);
        // 守卫守 4，狼刀 4，女巫救 4
        nightStep(g, 4, 4, 4, 0, 5, 0, 0);
        check(!g.bySeat(4).alive, "同守同救→目标死亡", "seat4 alive=" + g.bySeat(4).alive);
    }

    /** 守卫守护成功（女巫不救）→ 目标存活。 */
    static void testGuardProtect() {
        GameEngine g = new GameEngine(List.of(
                Role.GUARD, Role.WEREWOLF, Role.WITCH, Role.VILLAGER, Role.SEER, Role.VILLAGER), true);
        nightStep(g, 4, 4, 0, 0, 5, 0, 0);
        check(g.bySeat(4).alive, "守护成功→目标存活", "seat4 alive=" + g.bySeat(4).alive);
    }

    /** 女巫救人成功（守卫未守该人）→ 目标存活。 */
    static void testWitchSave() {
        GameEngine g = new GameEngine(List.of(
                Role.GUARD, Role.WEREWOLF, Role.WITCH, Role.VILLAGER, Role.SEER, Role.VILLAGER), true);
        nightStep(g, 6, 4, 4, 0, 5, 0, 0); // 守 6，刀 4，救 4
        check(g.bySeat(4).alive, "女巫救人成功→目标存活", "seat4 alive=" + g.bySeat(4).alive);
    }

    /** 猎人被狼刀 → 天亮可开枪带走一人。 */
    static void testHunterShootOnNightDeath() {
        GameEngine g = new GameEngine(List.of(
                Role.GUARD, Role.WEREWOLF, Role.WITCH, Role.HUNTER, Role.SEER, Role.VILLAGER), true);
        nightStep(g, 0, 4, 0, 0, 5, 0, 0); // 狼刀 4=猎人
        // 猎人死亡进入开枪阶段
        boolean reachedShoot = g.phase() == GamePhase.SHOOT && g.currentShooter() == 4;
        check(reachedShoot, "猎人被刀→进入开枪阶段", "phase=" + g.phase() + " shooter=" + g.currentShooter());
        if (reachedShoot) {
            g.submitShoot(4, 2); // 猎人开枪带走狼 2
            check(!g.bySeat(2).alive, "猎人开枪带走目标", "seat2 alive=" + g.bySeat(2).alive);
        }
    }

    /** 白痴被放逐 → 翻牌免死、失去投票权但仍存活。 */
    static void testIdiotReveal() {
        GameEngine g = new GameEngine(List.of(
                Role.WEREWOLF, Role.IDIOT, Role.SEER, Role.VILLAGER, Role.VILLAGER, Role.VILLAGER), true);
        nightStep(g, 0, 0, 0, 0, 3, 0, 0); // 空刀，无人死
        toDayVote(g);
        check(g.phase() == GamePhase.DAY_VOTE, "白痴局进入投票", "phase=" + g.phase());
        // 2..6 投白痴(2)，白痴弃票
        for (int v : List.of(1, 3, 4, 5, 6)) g.submitVote(v, 2);
        g.submitVote(2, 0);
        check(g.bySeat(2).alive && g.bySeat(2).idiotRevealed, "白痴翻牌免死",
                "alive=" + g.bySeat(2).alive + " revealed=" + g.bySeat(2).idiotRevealed);
        check(!g.bySeat(2).canVote(), "翻牌白痴失去投票权", "canVote=" + g.bySeat(2).canVote());
    }

    /** 猎人被女巫毒 → 不能开枪。 */
    static void testHunterPoisonedNoShoot() {
        GameEngine g = new GameEngine(List.of(
                Role.GUARD, Role.WEREWOLF, Role.WITCH, Role.HUNTER, Role.SEER, Role.VILLAGER), true);
        nightStep(g, 0, 0, 0, 4, 5, 0, 0); // 空刀，女巫毒 4=猎人
        check(!g.bySeat(4).alive, "猎人被毒死亡", "seat4 alive=" + g.bySeat(4).alive);
        check(g.phase() != GamePhase.SHOOT || g.currentShooter() != 4, "被毒猎人不开枪",
                "phase=" + g.phase() + " shooter=" + g.currentShooter());
    }

    /** 狼王被放逐 → 可开枪（区别于被刀/被毒不开枪）。 */
    static void testWolfKingShootOnExile() {
        GameEngine g = new GameEngine(List.of(
                Role.WOLF_KING, Role.WEREWOLF, Role.SEER, Role.VILLAGER, Role.VILLAGER, Role.VILLAGER), true);
        nightStep(g, 0, 0, 0, 0, 3, 0, 0); // 空刀
        toDayVote(g);
        for (int v : List.of(2, 3, 4, 5, 6)) g.submitVote(v, 1); // 全投狼王(1)
        g.submitVote(1, 0);
        boolean reachedShoot = g.phase() == GamePhase.SHOOT && g.currentShooter() == 1;
        check(reachedShoot, "狼王被放逐→进入开枪阶段", "phase=" + g.phase() + " shooter=" + g.currentShooter());
    }

    /** 好人把唯一的狼投出去 → 好人胜。 */
    static void testGoodWinByExilingLastWolf() {
        GameEngine g = new GameEngine(List.of(
                Role.WEREWOLF, Role.SEER, Role.VILLAGER, Role.VILLAGER, Role.VILLAGER, Role.VILLAGER), true);
        nightStep(g, 0, 0, 0, 0, 2, 0, 0); // 空刀
        toDayVote(g);
        for (int v : List.of(2, 3, 4, 5, 6)) g.submitVote(v, 1);
        g.submitVote(1, 0);
        // 放逐者有遗言，先走完遗言再判定胜负
        if (g.phase() == GamePhase.LAST_WORDS) g.submitLastWords(g.currentLastWords(), "");
        check(g.isGameOver() && g.winner() == Faction.VILLAGER, "投出唯一狼→好人胜",
                "over=" + g.isGameOver() + " winner=" + g.winner() + " phase=" + g.phase());
    }

    /* ---------- 功能道具用例 ---------- */

    /** 护盾：携带者首次被狼刀免疫存活。 */
    static void testItemShield() {
        GameEngine g = new GameEngine(List.of(
                Role.GUARD, Role.WEREWOLF, Role.WITCH, Role.VILLAGER, Role.SEER, Role.VILLAGER), true);
        g.applyItems(Map.of(4, "SHIELD"), 42); // 4 号带护盾
        check(g.bySeat(4).shielded, "护盾已附加到 4 号", "shielded=" + g.bySeat(4).shielded);
        nightStep(g, 0, 4, 0, 0, 5, 0, 0); // 狼刀 4，无人救/守
        check(g.bySeat(4).alive, "护盾抵挡狼刀→4号存活", "alive=" + g.bySeat(4).alive);
        check(!g.bySeat(4).shielded, "护盾已消耗", "shielded=" + g.bySeat(4).shielded);
    }

    /** 查杀卡：开局揭示一名玩家阵营，产生 ITEM_REVEAL 私有事件。 */
    static void testItemReveal() {
        GameEngine g = new GameEngine(List.of(
                Role.GUARD, Role.WEREWOLF, Role.WITCH, Role.VILLAGER, Role.SEER, Role.VILLAGER), true);
        g.applyItems(Map.of(4, "REVEAL"), 7);
        boolean has = g.events().stream().anyMatch(e -> e.type().equals(GameEvent.ITEM_REVEAL) && e.actorSeat() == 4);
        check(has, "查杀卡产生 ITEM_REVEAL 事件(4号)", "events=" + g.events().size());
    }

    /** 沉默卡：使一名非持有者首日禁言。 */
    static void testItemSilence() {
        GameEngine g = new GameEngine(List.of(
                Role.WEREWOLF, Role.VILLAGER, Role.VILLAGER, Role.VILLAGER, Role.SEER, Role.WITCH), true);
        g.applyItems(Map.of(1, "SILENCE"), 9);
        boolean any = g.players().stream().anyMatch(p -> p.seat != 1 && p.silencedToday);
        check(any, "沉默卡使某对手首日禁言", "silenced=" + g.players().stream().filter(p -> p.silencedToday).map(p -> p.seat).toList());
    }

    /** 复活卡：首次被狼刀原地复活（保留全部权利，卡消耗）。 */
    static void testItemReviveNight() {
        GameEngine g = new GameEngine(List.of(
                Role.GUARD, Role.WEREWOLF, Role.WITCH, Role.VILLAGER, Role.SEER, Role.VILLAGER), true);
        g.applyItems(Map.of(4, "REVIVE"), 42);
        nightStep(g, 0, 4, 0, 0, 5, 0, 0); // 狼刀 4
        check(g.bySeat(4).alive, "复活卡抵挡狼刀→4号原地复活", "alive=" + g.bySeat(4).alive);
        check(!g.bySeat(4).reviveAvailable, "复活卡已消耗", "revive=" + g.bySeat(4).reviveAvailable);
        boolean effect = g.events().stream().anyMatch(e -> e.type().equals(GameEvent.ITEM_EFFECT) && e.detail().contains("复活卡"));
        check(effect, "复活卡产生 ITEM_EFFECT 事件", "events=" + g.events().size());
    }

    /** 复活卡：被女巫毒杀同样原地复活。 */
    static void testItemRevivePoison() {
        GameEngine g = new GameEngine(List.of(
                Role.GUARD, Role.WEREWOLF, Role.WITCH, Role.VILLAGER, Role.SEER, Role.VILLAGER), true);
        g.applyItems(Map.of(4, "REVIVE"), 11);
        nightStep(g, 0, 0, 0, 4, 5, 0, 0); // 空刀 + 毒 4
        check(g.bySeat(4).alive, "复活卡抵挡毒杀→4号存活", "alive=" + g.bySeat(4).alive);
    }

    /** 复活卡：被放逐时原地复活。 */
    static void testItemReviveExile() {
        GameEngine g = new GameEngine(List.of(
                Role.WEREWOLF, Role.SEER, Role.VILLAGER, Role.VILLAGER, Role.VILLAGER, Role.VILLAGER), true);
        g.applyItems(Map.of(2, "REVIVE"), 13);
        nightStep(g, 0, 0, 0, 0, 2, 0, 0);
        toDayVote(g);
        for (int v : List.of(1, 3, 4, 5, 6)) g.submitVote(v, 2);
        g.submitVote(2, 0);
        if (g.phase() == GamePhase.LAST_WORDS) g.submitLastWords(g.currentLastWords(), "");
        check(g.bySeat(2).alive, "复活卡抵挡放逐→2号存活", "alive=" + g.bySeat(2).alive + " phase=" + g.phase());
        check(!g.bySeat(2).reviveAvailable, "复活卡已消耗", "revive=" + g.bySeat(2).reviveAvailable);
    }

    /** 铁布衫：首次被放逐免疫出局且保留投票权；狼刀不受影响。 */
    static void testItemIron() {
        GameEngine g = new GameEngine(List.of(
                Role.WEREWOLF, Role.SEER, Role.VILLAGER, Role.VILLAGER, Role.VILLAGER, Role.VILLAGER), true);
        g.applyItems(Map.of(3, "IRON"), 21);
        nightStep(g, 0, 0, 0, 0, 2, 0, 0);
        toDayVote(g);
        for (int v : List.of(1, 2, 4, 5, 6)) g.submitVote(v, 3);
        g.submitVote(3, 0);
        if (g.phase() == GamePhase.LAST_WORDS) g.submitLastWords(g.currentLastWords(), "");
        check(g.bySeat(3).alive, "铁布衫抵挡放逐→3号存活", "alive=" + g.bySeat(3).alive);
        check(g.bySeat(3).canVote(), "铁布衫保留投票权", "canVote=" + g.bySeat(3).canVote());
        check(!g.bySeat(3).ironSkin, "铁布衫已消耗", "iron=" + g.bySeat(3).ironSkin);
    }

    /* ================= 驱动辅助 ================= */

    /** 按当前夜晚阶段依次提交固定动作，直到夜晚结束（进入天亮及后续）。 */
    static void nightStep(GameEngine g, int guardT, int wolfT, int save, int poison, int seerT, int crowT, int silT) {
        int guard = 0;
        while (g.phase().isNight() && guard++ < 50) {
            switch (g.phase()) {
                case NIGHT_GUARD -> g.submitGuard(guardT);
                case NIGHT_WOLF -> g.submitWolfKill(g.nextWolfToAct(), wolfT);
                case NIGHT_WITCH -> g.submitWitch(save, poison);
                case NIGHT_SEER -> g.submitSeer(seerT);
                case NIGHT_CROW -> g.submitCrow(crowT);
                case NIGHT_SILENCER -> g.submitSilencer(silT);
                default -> { return; }
            }
        }
    }

    /** 推进到放逐投票阶段（跳过警长竞选与发言）。 */
    static void toDayVote(GameEngine g) {
        int guard = 0;
        while (guard++ < 200) {
            GamePhase p = g.phase();
            if (p == GamePhase.DAY_VOTE) return;
            if (p == GamePhase.SHERIFF_ELECTION) {
                if (g.sheriffSignupActive()) g.submitSheriffSignup(g.sheriffSignupCursor(), false);
                else {
                    int v = g.nextSheriffVoter();
                    if (v == 0) return;
                    g.submitSheriffVote(v, 0);
                }
            } else if (p == GamePhase.DAY_SPEAK) {
                g.submitSpeech(g.currentSpeaker(), "");
            } else if (p == GamePhase.LAST_WORDS) {
                g.submitLastWords(g.currentLastWords(), "");
            } else if (p == GamePhase.SHOOT) {
                g.submitShoot(g.currentShooter(), 0);
            } else {
                return;
            }
        }
    }

    /* ================= 断言 ================= */

    static void check(boolean cond, String name, String detail) {
        if (cond) { passed++; System.out.println("  [PASS] " + name); }
        else { failed++; System.out.println("  [FAIL] " + name + "  (" + detail + ")"); }
    }
}
