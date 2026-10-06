package com.werewolf.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 兑换记录（防止同一用户重复兑换同一码）。 */
@Entity
@Table(name = "redeem_usage",
        uniqueConstraints = @UniqueConstraint(columnNames = {"code_id", "user_id"}))
public class RedeemUsage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code_id", nullable = false)
    private Long codeId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCodeId() { return codeId; }
    public void setCodeId(Long codeId) { this.codeId = codeId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
