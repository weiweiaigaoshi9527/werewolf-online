package com.werewolf.rules.sim;

import com.werewolf.rules.*;

import java.util.*;

/**
 * 玩家决策策略接口。M2 用随机策略验证规则完备性；M5 的 AI 策略实现同一接口。
 * 所有方法返回“动作参数”，由驱动方提交给引擎；引擎负责合法性校验。
 */
public interface PlayerPolicy {

    int guardTarget(GameEngine g, Player me, Random rnd);
    int wolfKill(GameEngine g, Player me, Random rnd);
    /** 返回 [saveTarget, poisonTarget]，0 表示不使用。 */
    int[] witch(GameEngine g, Player me, Random rnd);
    int seerTarget(GameEngine g, Player me, Random rnd);
    int crowTarget(GameEngine g, Player me, Random rnd);
    int silencerTarget(GameEngine g, Player me, Random rnd);
    boolean sheriffSignup(GameEngine g, Player me, Random rnd);
    int sheriffVote(GameEngine g, Player me, List<Integer> candidates, Random rnd);
    void speak(GameEngine g, Player me, Random rnd);
    int exileVote(GameEngine g, Player me, Random rnd);
    int shootTarget(GameEngine g, Player me, Random rnd);
}
