package com.werewolf.rules;

/**
 * 事件溯源：所有对局动作 append 到不可变事件流。
 * 供回放、AI 上下文、审计、服务重启恢复共用。
 */
public record GameEvent(
        int seq,
        GamePhase phase,
        int day,
        String type,
        int actorSeat,
        int targetSeat,
        String detail) {

    /* ---------- 常用类型常量 ---------- */
    public static final String GAME_START = "GAME_START";
    public static final String NIGHT_ACTION = "NIGHT_ACTION";
    public static final String NIGHT_RESOLVE = "NIGHT_RESOLVE";
    public static final String DAWN_ANNOUNCE = "DAWN_ANNOUNCE";
    public static final String SHERIFF_SIGNUP = "SHERIFF_SIGNUP";
    public static final String SHERIFF_WIN = "SHERIFF_WIN";
    public static final String LAST_WORDS = "LAST_WORDS";
    public static final String SPEECH = "SPEECH";
    public static final String VOTE = "VOTE";
    public static final String VOTE_RESULT = "VOTE_RESULT";
    public static final String VOTE_DETAIL = "VOTE_DETAIL";   // 投票结束后公开每张票的去向
    public static final String VOTE_TIE = "VOTE_TIE";
    public static final String PLAYER_DIED = "PLAYER_DIED";
    public static final String SHOOT = "SHOOT";
    public static final String IDIOT_REVEAL = "IDIOT_REVEAL";
    public static final String WHITE_WOLF_BLOWUP = "WHITE_WOLF_BLOWUP";
    public static final String GAME_OVER = "GAME_OVER";
    public static final String ITEM_REVEAL = "ITEM_REVEAL";   // 查杀卡揭示（私有，仅持有者可见）
    public static final String ITEM_EFFECT = "ITEM_EFFECT";   // 护盾/沉默等道具生效（公开提示）
}
