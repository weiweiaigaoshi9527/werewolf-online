package com.werewolf.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 兑换码：可发放金币/经验/道具，限量、可过期、可停用。 */
@Entity
@Table(name = "redeem_code")
public class RedeemCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long gold = 0;

    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long exp = 0;

    /** 赠送道具（ItemDefinition.id，0=不送）。 */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int itemDefId = 0;

    @Column(nullable = false, columnDefinition = "integer default 1")
    private int maxUses = 1;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int usedCount = 0;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean active = true;

    private LocalDateTime expireAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(length = 64)
    private String note;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public long getGold() { return gold; }
    public void setGold(long gold) { this.gold = gold; }
    public long getExp() { return exp; }
    public void setExp(long exp) { this.exp = exp; }
    public int getItemDefId() { return itemDefId; }
    public void setItemDefId(int itemDefId) { this.itemDefId = itemDefId; }
    public int getMaxUses() { return maxUses; }
    public void setMaxUses(int maxUses) { this.maxUses = maxUses; }
    public int getUsedCount() { return usedCount; }
    public void setUsedCount(int usedCount) { this.usedCount = usedCount; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
