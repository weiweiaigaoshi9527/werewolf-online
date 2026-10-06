package com.werewolf.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 论坛回复。 */
@Entity
@Table(name = "forum_reply")
public class ForumReply {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 2000)
    private String content;

    @Column(nullable = false)
    private boolean hidden = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPostId() { return postId; }
    public void setPostId(Long v) { this.postId = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
    public boolean isHidden() { return hidden; }
    public void setHidden(boolean v) { this.hidden = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
