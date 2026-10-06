package com.werewolf.model;

import jakarta.persistence.*;

/** 商品/道具定义。type: AVATAR/FRAME/TITLE/NICKCOLOR/FUNCTION。 */
@Entity
@Table(name = "item_definition")
public class ItemDefinition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String type;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 32)
    private String name;

    @Column(length = 128)
    private String description;

    @Column(nullable = false)
    private int price;

    /** 装饰资源：emoji / 十六进制色值 / 图标标识 */
    @Column(length = 64)
    private String asset;

    @Column(nullable = false)
    private boolean enabled = true;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getPrice() { return price; }
    public void setPrice(int price) { this.price = price; }
    public String getAsset() { return asset; }
    public void setAsset(String asset) { this.asset = asset; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
