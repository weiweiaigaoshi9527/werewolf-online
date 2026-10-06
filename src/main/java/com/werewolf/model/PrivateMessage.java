package com.werewolf.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 私聊/系统消息。type: chat（普通私聊）/ invite（房间邀请）。 */
@Entity
@Table(name = "private_message",
        indexes = {
                @Index(name = "idx_pm_pair", columnList = "from_id,to_id"),
                // 收件箱未读统计：to_id + read_by_to
                @Index(name = "idx_pm_to_read", columnList = "to_id, read_by_to"),
                // 会话按时间排序：from_id + to_id + id
                @Index(name = "idx_pm_pair_id", columnList = "from_id, to_id, id")
        })
public class PrivateMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_id", nullable = false)
    private Long fromId;

    @Column(name = "to_id", nullable = false)
    private Long toId;

    @Column(nullable = false, length = 8)
    private String type = "chat";

    // 私聊内容有明确长度上限，去掉 @Lob 避免被 Hibernate 当作无长度限制的 CLOB
    @Column(length = 4000)
    private String content;

    /** 邀请类消息携带的房间号 */
    @Column(length = 6)
    private String roomNo;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean readByTo = false;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFromId() { return fromId; }
    public void setFromId(Long fromId) { this.fromId = fromId; }
    public Long getToId() { return toId; }
    public void setToId(Long toId) { this.toId = toId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getRoomNo() { return roomNo; }
    public void setRoomNo(String roomNo) { this.roomNo = roomNo; }
    public boolean isReadByTo() { return readByTo; }
    public void setReadByTo(boolean v) { this.readByTo = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
