package com.werewolf.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 金币流水：全量记录获取/消费/扣除，含对局来源。 */
@Entity
@Table(name = "gold_transaction",
        indexes = @Index(name = "idx_gt_user", columnList = "user_id, id"))
public class GoldTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private long delta;

    @Column(nullable = false)
    private long balanceAfter;

    @Column(nullable = false, length = 64)
    private String reason;

    private Long gameId;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public long getDelta() { return delta; }
    public void setDelta(long delta) { this.delta = delta; }
    public long getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(long balanceAfter) { this.balanceAfter = balanceAfter; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getGameId() { return gameId; }
    public void setGameId(Long gameId) { this.gameId = gameId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
