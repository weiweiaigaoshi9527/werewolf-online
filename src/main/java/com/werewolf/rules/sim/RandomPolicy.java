package com.werewolf.rules.sim;

import com.werewolf.rules.*;

import java.util.*;

/** 随机策略：只产生合法动作，用于规则引擎的完备性与健壮性压力测试。 */
public class RandomPolicy implements PlayerPolicy {

    private List<Integer> aliveSeats(GameEngine g) { return g.alive().stream().map(p -> p.seat).toList(); }
    private List<Integer> aliveGoods(GameEngine g) { return g.aliveGood().stream().map(p -> p.seat).toList(); }

    @Override
    public int guardTarget(GameEngine g, Player me, Random rnd) {
        List<Integer> opts = new ArrayList<>(aliveSeats(g));
        opts.add(0); // 空守
        opts.removeIf(s -> s == me.guardLastTarget);
        return opts.get(rnd.nextInt(opts.size()));
    }

    @Override
    public int wolfKill(GameEngine g, Player me, Random rnd) {
        List<Integer> goods = aliveGoods(g);
        if (goods.isEmpty() || rnd.nextInt(10) == 0) return 0; // 偶尔空刀
        return goods.get(rnd.nextInt(goods.size()));
    }

    @Override
    public int[] witch(GameEngine g, Player me, Random rnd) {
        int killed = g.nightWolfKill();
        boolean canSave = me.witchSaveAvailable && killed != 0;
        boolean canPoison = me.witchPoisonAvailable;
        // 简单启发：有刀且过半概率救人
        if (canSave && rnd.nextBoolean()) return new int[]{killed, 0};
        if (canPoison && rnd.nextInt(4) == 0) {
            List<Integer> targets = new ArrayList<>(aliveSeats(g));
            targets.removeIf(s -> s == me.seat);
            if (!targets.isEmpty()) return new int[]{0, targets.get(rnd.nextInt(targets.size()))};
        }
        return new int[]{0, 0};
    }

    @Override
    public int seerTarget(GameEngine g, Player me, Random rnd) {
        List<Integer> opts = new ArrayList<>(aliveSeats(g));
        opts.removeAll(me.seerChecked);
        if (opts.isEmpty()) opts = new ArrayList<>(aliveSeats(g));
        return opts.get(rnd.nextInt(opts.size()));
    }

    @Override
    public int crowTarget(GameEngine g, Player me, Random rnd) {
        if (rnd.nextBoolean()) return 0;
        List<Integer> opts = aliveSeats(g);
        return opts.get(rnd.nextInt(opts.size()));
    }

    @Override
    public int silencerTarget(GameEngine g, Player me, Random rnd) {
        List<Integer> opts = new ArrayList<>(aliveSeats(g));
        opts.add(0);
        opts.removeIf(s -> s == me.silencerLastTarget);
        return opts.get(rnd.nextInt(opts.size()));
    }

    @Override
    public boolean sheriffSignup(GameEngine g, Player me, Random rnd) {
        // 神职与狼人更爱上警
        int bias = me.role.isGod() ? 60 : (me.isWolf() ? 45 : 25);
        return rnd.nextInt(100) < bias;
    }

    @Override
    public int sheriffVote(GameEngine g, Player me, List<Integer> candidates, Random rnd) {
        if (candidates.isEmpty() || rnd.nextInt(8) == 0) return 0;
        return candidates.get(rnd.nextInt(candidates.size()));
    }

    @Override
    public void speak(GameEngine g, Player me, Random rnd) { /* 文本由驱动方填充 */ }

    @Override
    public int exileVote(GameEngine g, Player me, Random rnd) {
        if (g.inTiebreak()) {
            List<Integer> opts = new ArrayList<>(g.tiebreakCandidates());
            opts.add(0);
            return opts.get(rnd.nextInt(opts.size()));
        }
        List<Integer> opts = new ArrayList<>(aliveSeats(g));
        opts.removeIf(s -> s == me.seat);
        opts.removeIf(s -> { Player p = g.bySeat(s); return p == null || p.idiotRevealed; });
        opts.add(0);
        return opts.get(rnd.nextInt(opts.size()));
    }

    @Override
    public int shootTarget(GameEngine g, Player me, Random rnd) {
        List<Integer> opts = new ArrayList<>(aliveSeats(g));
        opts.removeIf(s -> s == me.seat);
        opts.add(0); // 可能不开枪（虽然引擎只在 canShootOnDeath 时入队）
        return opts.get(rnd.nextInt(opts.size()));
    }
}
