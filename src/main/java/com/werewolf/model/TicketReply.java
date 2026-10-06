package com.werewolf.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 工单回复（用户与管理员往来）。 */
@Entity
@Table(name = "ticket_reply",
        indexes = @Index(name = "idx_tr_ticket", columnList = "ticket_id"))
public class TicketReply {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean admin = false;

    // 回复内容有明确长度上限，去掉 @Lob 避免被 Hibernate 当作无长度限制的 CLOB
    @Column(length = 4000)
    private String content;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
    public Long getAuthorId() { return authorId; }
    public void setAuthorId(Long authorId) { this.authorId = authorId; }
    public boolean isAdmin() { return admin; }
    public void setAdmin(boolean admin) { this.admin = admin; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
