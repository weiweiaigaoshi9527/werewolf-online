package com.werewolf.game;

/** 座位信息：真人或托管补位。userId 对托管座位为负数合成值。 */
public class SeatInfo {
    public final int seat;
    public final long userId;      // 真人=真实id；托管=-(seat)
    public final String nickname;
    public final int avatarId;
    public final boolean bot;
    public final String avatarUrl; // 真人自定义头像
    public final String nickColor;
    public final String frameColor;
    public final String title;
    public final int vip;   // VIP 等级（用于徽标/入场特效）

    public SeatInfo(int seat, long userId, String nickname, int avatarId, boolean bot) {
        this(seat, userId, nickname, avatarId, bot, null, null, null, null, 0);
    }

    public SeatInfo(int seat, long userId, String nickname, int avatarId, boolean bot,
                    String avatarUrl, String nickColor, String frameColor, String title) {
        this(seat, userId, nickname, avatarId, bot, avatarUrl, nickColor, frameColor, title, 0);
    }

    public SeatInfo(int seat, long userId, String nickname, int avatarId, boolean bot,
                    String avatarUrl, String nickColor, String frameColor, String title, int vip) {
        this.seat = seat;
        this.userId = userId;
        this.nickname = nickname;
        this.avatarId = avatarId;
        this.bot = bot;
        this.avatarUrl = avatarUrl;
        this.nickColor = nickColor;
        this.frameColor = frameColor;
        this.title = title;
        this.vip = vip;
    }
}
