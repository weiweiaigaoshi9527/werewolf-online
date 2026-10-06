package com.werewolf.model;

import jakarta.persistence.*;

/** AI 玩家后台配置（单例，id=1）。 */
@Entity
@Table(name = "ai_config")
public class AiConfig {
    @Id
    private Long id = 1L;

    @Column(length = 256)
    private String baseUrl;              // OpenAI 兼容服务地址

    @Column(length = 512)
    private String apiKeyEnc;            // AES 加密存储

    @Column(length = 64)
    private String model;

    @Column(length = 8)
    private String depth = "medium";     // low / medium / high（思考深度）

    private double temperature = 0.8;

    private int timeoutSeconds = 30;
    private int maxRetries = 2;
    private int concurrency = 4;
    private long tokenBudgetPerGame = 200000;

    @Column(nullable = false)
    private boolean enabled = false;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKeyEnc() { return apiKeyEnc; }
    public void setApiKeyEnc(String apiKeyEnc) { this.apiKeyEnc = apiKeyEnc; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getDepth() { return depth; }
    public void setDepth(String depth) { this.depth = depth; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public long getTokenBudgetPerGame() { return tokenBudgetPerGame; }
    public void setTokenBudgetPerGame(long tokenBudgetPerGame) { this.tokenBudgetPerGame = tokenBudgetPerGame; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
