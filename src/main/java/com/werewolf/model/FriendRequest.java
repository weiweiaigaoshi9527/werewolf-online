package com.werewolf.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 好友申请。status: PENDING / ACCEPTED / REJECTED。 */
@Entity
@Table(name = "friend_request",
        // 注意：这里刻意不加 (from_id,to_id) 唯一约束。
        // 历史库中同一对用户可能存在多条申请（如先 REJECTED 后 ACCEPTED），
        // ddl-auto=update 下新增唯一约束会因历史重复数据导致启动失败。
        // 申请的唯一性（是否已有 PENDING 申请）改由 FriendService.sendRequest 在应用层保证。
        indexes = @Index(name = "idx_fr_to_status", columnList = "to_id, status"))
public class FriendRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_id", nullable = false)
    private Long fromId;

    @Column(name = "to_id", nullable = false)
    private Long toId;

    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    @Column(length = 64)
    private String greeting;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFromId() { return fromId; }
    public void setFromId(Long fromId) { this.fromId = fromId; }
    public Long getToId() { return toId; }
    public void setToId(Long toId) { this.toId = toId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getGreeting() { return greeting; }
    public void setGreeting(String greeting) { this.greeting = greeting; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
