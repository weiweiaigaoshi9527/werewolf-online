package com.werewolf.rules;

/** 对局阶段状态机。 */
public enum GamePhase {
    /** 发牌完成，等待首夜 */
    SETUP,
    /** 夜晚：守卫行动 */
    NIGHT_GUARD,
    /** 夜晚：狼人袭击（狼队内部投票合成刀型） */
    NIGHT_WOLF,
    /** 夜晚：女巫救/毒 */
    NIGHT_WITCH,
    /** 夜晚：预言家查验 */
    NIGHT_SEER,
    /** 夜晚：乌鸦诽谤 */
    NIGHT_CROW,
    /** 夜晚：禁言长老禁言 */
    NIGHT_SILENCER,
    /** 天亮：公布死讯 + 触发死亡结算 */
    DAWN,
    /** 首日：警长竞选（报名 → 发言 → 投票 → 当选） */
    SHERIFF_ELECTION,
    /** 遗言阶段 */
    LAST_WORDS,
    /** 白天：依次发言 */
    DAY_SPEAK,
    /** 白天：放逐投票 */
    DAY_VOTE,
    /** 白天：平票 PK（再次发言 + 投票） */
    DAY_VOTE_TIEBREAK,
    /** 死亡触发开枪（猎人/狼王） */
    SHOOT,
    /** 游戏结束 */
    GAME_OVER;

    public boolean isNight() {
        return this == NIGHT_GUARD || this == NIGHT_WOLF || this == NIGHT_WITCH
                || this == NIGHT_SEER || this == NIGHT_CROW || this == NIGHT_SILENCER;
    }
}
