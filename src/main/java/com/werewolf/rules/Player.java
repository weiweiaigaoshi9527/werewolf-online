package com.werewolf.rules;

import java.util.ArrayList;
import java.util.List;

/** 玩家运行时状态（可变）。座位号 1-based。 */
public class Player {

    public final int seat;
    public final long userId;
    public Role role;
    public boolean alive = true;

    /** 女巫药 */
    public boolean witchSaveAvailable = true;
    public boolean witchPoisonAvailable = true;

    /** 守卫上夜守护目标（禁止连续两晚同守） */
    public int guardLastTarget = 0;

    /** 预言家已查验过的座位（可选优化：允许重复验） */
    public final List<Integer> seerChecked = new ArrayList<>();

    /** 禁言长老上夜目标（禁止连续两晚同禁） */
    public int silencerLastTarget = 0;

    /** 白天被禁言（天亮重置） */
    public boolean silencedToday = false;

    /** 被乌鸦诽谤（放逐投票 +1，天亮重置） */
    public boolean accusedByCrow = false;

    /** 白痴已翻牌：失去投票权，免疫放逐，保留发言 */
    public boolean idiotRevealed = false;

    /** 猎人/狼王：本次死亡是否可开枪（被毒不可） */
    public boolean canShootOnDeath = false;

    /** 白狼王：本局自爆是否可用 */
    public boolean whiteWolfCanBlowUp = true;

    /** 道具·护盾（免死金牌）：免疫本局首次狼刀 */
    public boolean shielded = false;

    /** 道具·守护水晶：免疫本局首次狼刀与首次毒杀 */
    public boolean shieldedFull = false;

    /** 道具·铁票：下一次放逐投票计为两票（使用后消耗） */
    public boolean doubleVote = false;

    /** 道具·替死娃娃：首次被放逐时免死（使用后消耗） */
    public boolean immuneExile = false;

    /** 道具·复活卡：首次死亡时原地复活（夜刀/毒/放逐/枪均可，使用后消耗） */
    public boolean reviveAvailable = false;

    /** 道具·铁布衫：首次被放逐时免疫出局（保留投票权，使用后消耗） */
    public boolean ironSkin = false;

    /** 是否为警长 */
    public boolean isSheriff = false;

    /** 死因（用于日志与遗言判定） */
    public String deathCause = null;
    public int deathDay = 0;

    public Player(int seat, long userId, Role role) {
        this.seat = seat;
        this.userId = userId;
        this.role = role;
    }

    public boolean isWolf() { return role.isWolf(); }
    public boolean isGood() { return role.faction().isGood(); }
    public boolean isGod() { return role.isGod(); }
    public boolean isVillager() { return role.isVillager(); }

    /** 是否有投票权：存活 + 非翻牌白痴 */
    public boolean canVote() {
        return alive && !idiotRevealed;
    }

    /** 是否可以发言：存活 + 未被禁言 */
    public boolean canSpeak() {
        return alive && !silencedToday;
    }

    @Override
    public String toString() {
        return "P" + seat + "(" + role.cnName() + (alive ? "" : ",†") + ")";
    }
}
