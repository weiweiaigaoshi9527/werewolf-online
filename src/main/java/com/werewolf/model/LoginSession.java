package com.werewolf.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 持久化登录会话：玩家选择“保留登录 X 天”时签发，服务重启后凭证仍有效。 */
@Entity
@Table(name = "login_session",
        indexes = {
                @Index(name = "idx_ls_token", columnList = "token_hash", unique = true),
                @Index(name = "idx_ls_user", columnList = "user_id")
        })
public class LoginSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** token 的 SHA-256 摘要（明文 token 只存在玩家本地，服务端只存摘要） */
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime expireAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public LocalDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
