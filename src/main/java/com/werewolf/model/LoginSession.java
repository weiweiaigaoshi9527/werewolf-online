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

    /**
     * 会话域：app=玩家端（游戏页面），admin=管理后台。
     * 同一账号在两个域里可以各自保持登录、互不挤掉（登录只在同域内做单会话互踢）。
     */
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) default 'app'")
    private String scope = "app";

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
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public LocalDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
