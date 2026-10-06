package com.werewolf.model;

import jakarta.persistence.*;

/** 单个真人玩家在一局中的战绩记录（供战绩统计与回放入口）。 */
@Entity
@Table(name = "match_participant",
        // 同一用户在同一局只应有一条战绩
        uniqueConstraints = @UniqueConstraint(name = "uk_mp_user_game", columnNames = {"user_id", "game_id"}),
        indexes = @Index(name = "idx_mp_user", columnList = "user_id, id"))
public class MatchParticipant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long gameId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private int seat;

    @Column(name = "role_name", nullable = false, length = 16)
    private String role;

    @Column(nullable = false, length = 8)
    private String faction; // WOLF / GOOD

    @Column(nullable = false)
    private boolean won;

    @Column(nullable = false)
    private boolean survived;

    @Column(nullable = false)
    private boolean mvp;

    @Column(name = "day_no", nullable = false)
    private int day; // 结束天数

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getGameId() { return gameId; }
    public void setGameId(Long gameId) { this.gameId = gameId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public int getSeat() { return seat; }
    public void setSeat(int seat) { this.seat = seat; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getFaction() { return faction; }
    public void setFaction(String faction) { this.faction = faction; }
    public boolean isWon() { return won; }
    public void setWon(boolean won) { this.won = won; }
    public boolean isSurvived() { return survived; }
    public void setSurvived(boolean survived) { this.survived = survived; }
    public boolean isMvp() { return mvp; }
    public void setMvp(boolean mvp) { this.mvp = mvp; }
    public int getDay() { return day; }
    public void setDay(int day) { this.day = day; }
}
