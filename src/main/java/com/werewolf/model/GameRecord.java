package com.werewolf.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "match_record",
        indexes = @Index(name = "idx_record_room", columnList = "room_id"))
public class GameRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long roomId;

    @Column(nullable = false, length = 6)
    private String roomNo;

    @Column(length = 256)
    private String board;

    @Column(length = 16)
    private String winner; // 狼人阵营 / 好人阵营

    @Column(nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    private LocalDateTime endedAt;

    @Column(length = 4000)
    private String seatRoles; // 座位->角色 快照（亮牌）

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }
    public String getRoomNo() { return roomNo; }
    public void setRoomNo(String roomNo) { this.roomNo = roomNo; }
    public String getBoard() { return board; }
    public void setBoard(String board) { this.board = board; }
    public String getWinner() { return winner; }
    public void setWinner(String winner) { this.winner = winner; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
    public String getSeatRoles() { return seatRoles; }
    public void setSeatRoles(String seatRoles) { this.seatRoles = seatRoles; }
}
