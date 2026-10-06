package com.werewolf.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "game_room")
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 6 位数字房间号，对外展示 */
    @Column(nullable = false, unique = true, length = 6)
    private String roomNo;

    @Column(nullable = false)
    private Long hostUserId;

    /** WAITING / PLAYING / FINISHED */
    @Column(nullable = false, length = 16)
    private String status = "WAITING";

    @Column(nullable = false)
    private int maxSeats = 16;

    /** 房主选择的板子配置（JSON：角色名->数量），null=用默认按人数自动选。 */
    @Column(length = 512)
    private String boardConfig;

    /** 板子展示名（预设 id 或 "custom"）。 */
    @Column(length = 32)
    private String boardName;

    /** 语音模式：真人麦克风经 ASR→TTS 同语种声纹中继，AI 逐句发声。需语音服务在线。 */
    @Column(name = "voice_mode", nullable = false, columnDefinition = "boolean default false")
    private boolean voiceMode = false;

    /** 匿名模式：全员头像/昵称固定伪装、隐藏 AI 身份（常与语音模式搭配以藏 AI）。 */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean anonymous = false;

    /** 胜负规则：false=屠边（默认，神/民任一灭尽即狼胜）；true=数量规则（狼数≥好人数才狼胜，俗称屠城向） */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean huntCity = false;

    /** 功能道具赛：开启时携带的功能道具本局生效；关闭则为纯逻辑局（道具不生效）。默认开启。 */
    @Column(name = "item_match", nullable = false, columnDefinition = "boolean default true")
    private boolean itemMatch = true;

    /** 双人组队模式：开启后玩家可互相发起组队邀请，同队成员保证同阵营。 */
    @Column(name = "duo_mode", nullable = false, columnDefinition = "boolean default false")
    private boolean duoMode = false;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRoomNo() { return roomNo; }
    public void setRoomNo(String roomNo) { this.roomNo = roomNo; }

    public Long getHostUserId() { return hostUserId; }
    public void setHostUserId(Long hostUserId) { this.hostUserId = hostUserId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getMaxSeats() { return maxSeats; }
    public void setMaxSeats(int maxSeats) { this.maxSeats = maxSeats; }

    public String getBoardConfig() { return boardConfig; }
    public void setBoardConfig(String boardConfig) { this.boardConfig = boardConfig; }

    public String getBoardName() { return boardName; }
    public void setBoardName(String boardName) { this.boardName = boardName; }

    public boolean isVoiceMode() { return voiceMode; }
    public void setVoiceMode(boolean voiceMode) { this.voiceMode = voiceMode; }

    public boolean isAnonymous() { return anonymous; }
    public void setAnonymous(boolean anonymous) { this.anonymous = anonymous; }

    public boolean isHuntCity() { return huntCity; }
    public void setHuntCity(boolean huntCity) { this.huntCity = huntCity; }

    public boolean isItemMatch() { return itemMatch; }
    public void setItemMatch(boolean itemMatch) { this.itemMatch = itemMatch; }

    public boolean isDuoMode() { return duoMode; }
    public void setDuoMode(boolean duoMode) { this.duoMode = duoMode; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
