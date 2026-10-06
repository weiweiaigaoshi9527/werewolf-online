package com.werewolf.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 背包条目：用户拥有的商品。 */
@Entity
@Table(name = "inventory",
        // 同一用户同一道具只保留一条记录
        uniqueConstraints = @UniqueConstraint(name = "uk_inv_user_item", columnNames = {"user_id", "item_def_id"}),
        indexes = @Index(name = "idx_inv_user", columnList = "user_id"))
public class Inventory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "item_def_id", nullable = false)
    private Long itemDefId;

    /** 功能道具是否已在对局中消耗（装饰类恒 false） */
    @Column(nullable = false)
    private boolean used = false;

    @Column(nullable = false)
    private LocalDateTime acquiredAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getItemDefId() { return itemDefId; }
    public void setItemDefId(Long itemDefId) { this.itemDefId = itemDefId; }
    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }
    public LocalDateTime getAcquiredAt() { return acquiredAt; }
    public void setAcquiredAt(LocalDateTime acquiredAt) { this.acquiredAt = acquiredAt; }
}
