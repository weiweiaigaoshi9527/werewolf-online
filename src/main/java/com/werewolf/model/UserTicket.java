package com.werewolf.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 用户反馈 / 举报工单。kind: FEEDBACK / REPORT。status: OPEN / HANDLED。 */
@Entity
@Table(name = "user_ticket")
public class UserTicket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String kind = "FEEDBACK";

    /** 工单分类：问题 / 建议 / 举报 / 其他。 */
    @Column(length = 16)
    private String category = "问题";

    /** 工单标题。 */
    @Column(length = 80)
    private String title;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    /** 被举报用户（REPORT 时）。 */
    @Column(name = "target_id")
    private Long targetId;

    /** 关联房间号（可选）。 */
    @Column(length = 6)
    private String roomNo;

    // 工单内容有明确长度上限，去掉 @Lob 避免被 Hibernate 当作无长度限制的 CLOB
    @Column(length = 4000)
    private String content;

    @Column(nullable = false, length = 16)
    private String status = "OPEN";

    @Column(length = 500)
    private String adminNote;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Long getReporterId() { return reporterId; }
    public void setReporterId(Long reporterId) { this.reporterId = reporterId; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public String getRoomNo() { return roomNo; }
    public void setRoomNo(String roomNo) { this.roomNo = roomNo; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAdminNote() { return adminNote; }
    public void setAdminNote(String adminNote) { this.adminNote = adminNote; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
