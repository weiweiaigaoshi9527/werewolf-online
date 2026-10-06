package com.werewolf.game;

/** 玩家提交的动作载荷（按当前 actionKind 取用相应字段）。 */
public class GameAction {
    public int target;        // 通用目标座位（0=无/放弃/弃票）
    public int saveTarget;    // 女巫解药目标
    public int poisonTarget;  // 女巫毒药目标
    public String text;       // 发言/遗言文本
    public boolean signup;    // 警长报名与否

    public GameAction() {}
    public GameAction(int target) { this.target = target; }
}
