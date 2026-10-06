package com.werewolf.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "app_user",
        indexes = @Index(name = "idx_user_nickname", columnList = "nickname"))
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 32)
    private String nickname;

    /** 预留邮箱字段（首期不用，为公网化扩展准备） */
    @Column(length = 128)
    private String email;

    @Column(nullable = false)
    private int avatarId = 1;

    /** 上传的自定义头像 URL（优先于 avatarId 的默认库） */
    @Column(length = 256)
    private String avatarUrl;

    /** 装备的头像框（ItemDefinition.id，0=无） */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int frameId = 0;

    /** 装备的称号（ItemDefinition.id，0=无） */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int titleId = 0;

    /** 昵称颜色（十六进制色值，null=默认） */
    @Column(length = 16)
    private String nickColor;

    /** 上次改名时间（限频用） */
    private LocalDateTime nicknameUpdatedAt;

    /** 本局携带的功能道具（ItemDefinition.id，null=不带） */
    @Column(name = "carry_item_id")
    private Long carryItemId;

    @Column(nullable = false)
    private int level = 1;

    @Column(nullable = false)
    private long exp = 0;

    @Column(nullable = false)
    private long gold = 100;

    /** 生日（用户自填，字符串 yyyy-MM-dd，可空）。 */
    @Column(length = 16)
    private String birthday;

    /** 所在地（用户自填，可空）。 */
    @Column(length = 32)
    private String location;

    /** 注册来源地（自填或“内网”，可空）。 */
    @Column(name = "reg_location", length = 32)
    private String regLocation;

    /** 个性签名（用户自填，可空）。 */
    @Column(length = 60)
    private String signature;

    /** 上次在线时间。 */
    private LocalDateTime lastOnlineAt;

    /** 累计在线秒数。 */
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long onlineSeconds = 0;

    /** 上次签到日期。 */
    private java.time.LocalDate lastCheckIn;

    /** 连续签到天数。 */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int checkinStreak = 0;

    /** 语音性别偏好：F=女声 / M=男声 / null=按座位自动。语音模式下朗读用该性别音色。 */
    @Column(name = "voice_gender", length = 2)
    private String voiceGender;

    /** VIP 等级：0=普通，1=白银，2=黄金，3=钻石。 */
    @Column(name = "vip_level", nullable = false, columnDefinition = "integer default 0")
    private int vipLevel = 0;

    /** VIP 到期时间（null=不过期/未开通）。 */
    @Column(name = "vip_expire_at")
    private LocalDateTime vipExpireAt;

    /** QQ 号（用户自填，5-11 位数字；用于展示与拉取 QQ 头像）。 */
    @Column(length = 16)
    private String qq;

    /** 是否已领取过"补填 QQ 奖励"（一次性 1000 金币，防重复领取）。 */
    @Column(name = "qq_rewarded", nullable = false, columnDefinition = "boolean default false")
    private boolean qqRewarded = false;

    /** 是否管理员（可访问后台）。首个注册者自动为 true，或由配置引导。 */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean admin = false;

    /** 是否被封禁（封禁后不能登录）。 */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean banned = false;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 乐观锁版本号：防止并发下的丢失更新（JPA 自动维护）。 */
    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public int getAvatarId() { return avatarId; }
    public void setAvatarId(int avatarId) { this.avatarId = avatarId; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public int getFrameId() { return frameId; }
    public void setFrameId(int frameId) { this.frameId = frameId; }

    public int getTitleId() { return titleId; }
    public void setTitleId(int titleId) { this.titleId = titleId; }

    public String getNickColor() { return nickColor; }
    public void setNickColor(String nickColor) { this.nickColor = nickColor; }

    public LocalDateTime getNicknameUpdatedAt() { return nicknameUpdatedAt; }
    public void setNicknameUpdatedAt(LocalDateTime nicknameUpdatedAt) { this.nicknameUpdatedAt = nicknameUpdatedAt; }

    public Long getCarryItemId() { return carryItemId; }
    public void setCarryItemId(Long carryItemId) { this.carryItemId = carryItemId; }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

    public long getExp() { return exp; }
    public void setExp(long exp) { this.exp = exp; }

    public long getGold() { return gold; }
    public void setGold(long gold) { this.gold = gold; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public boolean isAdmin() { return admin; }
    public void setAdmin(boolean admin) { this.admin = admin; }

    public boolean isBanned() { return banned; }
    public void setBanned(boolean banned) { this.banned = banned; }

    public String getBirthday() { return birthday; }
    public void setBirthday(String birthday) { this.birthday = birthday; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getRegLocation() { return regLocation; }
    public void setRegLocation(String regLocation) { this.regLocation = regLocation; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public LocalDateTime getLastOnlineAt() { return lastOnlineAt; }
    public void setLastOnlineAt(LocalDateTime v) { this.lastOnlineAt = v; }
    public long getOnlineSeconds() { return onlineSeconds; }
    public void setOnlineSeconds(long v) { this.onlineSeconds = v; }
    public java.time.LocalDate getLastCheckIn() { return lastCheckIn; }
    public void setLastCheckIn(java.time.LocalDate v) { this.lastCheckIn = v; }
    public int getCheckinStreak() { return checkinStreak; }
    public void setCheckinStreak(int v) { this.checkinStreak = v; }

    public String getVoiceGender() { return voiceGender; }
    public void setVoiceGender(String v) { this.voiceGender = v; }

    public int getVipLevel() { return vipLevel; }
    public void setVipLevel(int v) { this.vipLevel = v; }
    public LocalDateTime getVipExpireAt() { return vipExpireAt; }
    public void setVipExpireAt(LocalDateTime v) { this.vipExpireAt = v; }

    public String getQq() { return qq; }
    public void setQq(String v) { this.qq = v; }
    public boolean isQqRewarded() { return qqRewarded; }
    public void setQqRewarded(boolean v) { this.qqRewarded = v; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
