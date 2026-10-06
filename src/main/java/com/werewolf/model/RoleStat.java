package com.werewolf.model;

import jakarta.persistence.*;

/** 玩家各角色的使用统计（场次/胜场/累计时长秒）。 */
@Entity
@Table(name = "role_stat",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "role_name"}))
public class RoleStat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role_name", nullable = false, length = 16)
    private String roleName;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int games = 0;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int wins = 0;

    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long seconds = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }
    public int getGames() { return games; }
    public void setGames(int games) { this.games = games; }
    public int getWins() { return wins; }
    public void setWins(int wins) { this.wins = wins; }
    public long getSeconds() { return seconds; }
    public void setSeconds(long seconds) { this.seconds = seconds; }
}
