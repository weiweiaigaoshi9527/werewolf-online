package com.werewolf.model;

import jakarta.persistence.*;

/** 双人组队：同房间内两名玩家结成同阵营对子。 */
@Entity
@Table(name = "room_duo",
        indexes = @Index(name = "idx_rd_room", columnList = "room_id"))
public class RoomDuo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "user_a", nullable = false)
    private Long userA;

    @Column(name = "user_b", nullable = false)
    private Long userB;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }
    public Long getUserA() { return userA; }
    public void setUserA(Long userA) { this.userA = userA; }
    public Long getUserB() { return userB; }
    public void setUserB(Long userB) { this.userB = userB; }
}
