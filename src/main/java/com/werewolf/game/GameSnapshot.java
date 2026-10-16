package com.werewolf.game;

import com.werewolf.rules.GameEngine;

import java.io.Serializable;
import java.util.Map;

/**
 * 进行中对局的「可持久化快照」。
 *
 * <p>LiveGame 本身持有定时器、线程池 Future、监视器对象等不可序列化的运行时资源，
 * 因此不能直接序列化。这里只抽出「恢复对局所必需」的纯数据：规则引擎状态、座位表、
 * 用户到座位的映射、以及开局参数（seed/板子选项/房主等）。
 * 恢复时用这些字段重新 new 一个 LiveGame，再交给 LiveGameService 重新驱动即可。</p>
 */
public class GameSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 快照格式版本，便于将来结构变更时丢弃旧文件。 */
    public int version = 1;

    public long roomId;
    public String roomNo;

    /** 规则引擎完整状态（含全部玩家、事件流、阶段、投票等）。 */
    public GameEngine engine;

    public Map<Integer, SeatInfo> seats;
    public Map<Long, Integer> userToSeat;

    /** 开局随机种子：用于重建 LiveGame.rnd。 */
    public long seed;

    public boolean huntByBorder;
    public boolean voiceMode;
    public boolean anonymous;
    public boolean itemMatch;
    public boolean aiOnly;
    public long hostUserId;

    /** 已落库的对局记录 id（未结束时为 null）。 */
    public Long gameId;

    /** 快照写入时刻（毫秒），仅用于排查。 */
    public long savedAt;
}
