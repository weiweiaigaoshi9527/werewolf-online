package com.werewolf.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "room_player",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"room_id", "seat_no"}),
                @UniqueConstraint(columnNames = {"room_id", "user_id"})
        })
public class RoomPlayer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 座位号 1..maxSeats */
    @Column(name = "seat_no", nullable = false)
    private Integer seatNo;

    @Column(nullable = false)
    private boolean ready = false;

    /** 是否为补位 AI 玩家 */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean ai = false;

    /** AI 玩家的展示昵称（真人用 User.nickname） */
    @Column(length = 32)
    private String aiName;

    @Column(nullable = false)
    private LocalDateTime joinedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Integer getSeatNo() { return seatNo; }
    public void setSeatNo(Integer seatNo) { this.seatNo = seatNo; }

    public boolean isReady() { return ready; }
    public void setReady(boolean ready) { this.ready = ready; }

    public boolean isAi() { return ai; }
    public void setAi(boolean ai) { this.ai = ai; }

    public String getAiName() { return aiName; }
    public void setAiName(String aiName) { this.aiName = aiName; }

    public LocalDateTime getJoinedAt() { return joinedAt; }
    public void setJoinedAt(LocalDateTime joinedAt) { this.joinedAt = joinedAt; }
}
