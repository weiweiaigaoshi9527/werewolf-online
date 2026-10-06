package com.werewolf.model;

import jakarta.persistence.*;

/** 对局事件流（事件溯源，回放/AI 上下文/审计共用）。 */
@Entity
@Table(name = "match_event",
        // 回放按 (game_id, seq) 顺序拉取，索引调整为 game_id + seq
        indexes = @Index(name = "idx_match_event", columnList = "game_id, seq"))
public class GameEventLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long gameId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, length = 24)
    private String phase;

    @Column(name = "day_no", nullable = false)
    private int day;

    @Column(nullable = false, length = 24)
    private String type;

    private int actorSeat;
    private int targetSeat;

    @Column(length = 1000)
    private String detail;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getGameId() { return gameId; }
    public void setGameId(Long gameId) { this.gameId = gameId; }
    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public int getDay() { return day; }
    public void setDay(int day) { this.day = day; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public int getActorSeat() { return actorSeat; }
    public void setActorSeat(int actorSeat) { this.actorSeat = actorSeat; }
    public int getTargetSeat() { return targetSeat; }
    public void setTargetSeat(int targetSeat) { this.targetSeat = targetSeat; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
