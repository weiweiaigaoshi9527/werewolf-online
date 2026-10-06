package com.werewolf.model;

import jakarta.persistence.*;

/** 通用键值设置表（后台可持久化的配置，如语音参数 JSON）。 */
@Entity
@Table(name = "app_setting")
public class AppSetting {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "skey", nullable = false, unique = true, length = 64)
    private String key;

    // 配置值有明确长度上限，去掉 @Lob 避免被 Hibernate 当作无长度限制的 CLOB
    @Column(name = "sval", length = 20000)
    private String value;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
