package com.werewolf.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 论坛帖子。 */
@Entity
@Table(name = "forum_post")
public class ForumPost {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 80)
    private String title;

    @Column(nullable = false, length = 4000)
    private String content;

    @Column(length = 16)
    private String category = "综合";

    @Column(nullable = false)
    private boolean pinned = false;

    @Column(nullable = false)
    private boolean hidden = false;

    @Column(name = "reply_count", nullable = false)
    private int replyCount = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public boolean isPinned() { return pinned; }
    public void setPinned(boolean v) { this.pinned = v; }
    public boolean isHidden() { return hidden; }
    public void setHidden(boolean v) { this.hidden = v; }
    public int getReplyCount() { return replyCount; }
    public void setReplyCount(int v) { this.replyCount = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
