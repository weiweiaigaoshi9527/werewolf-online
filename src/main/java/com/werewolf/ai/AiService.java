package com.werewolf.ai;

import com.werewolf.model.AiConfig;
import com.werewolf.repo.AiConfigRepository;
import com.werewolf.service.CryptoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** AI 编排：读取配置、选择 provider、限并发、思考深度映射、配置加密存储。 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private final AiConfigRepository repo;
    private final OpenAiClient openAi = new OpenAiClient();
    private final MockProvider mock = new MockProvider();
    private volatile Semaphore semaphore;
    private volatile int semSize = -1;

    public AiService(AiConfigRepository repo) {
        this.repo = repo;
    }

    public AiConfig get() {
        return repo.findById(1L).orElseGet(() -> {
            AiConfig c = new AiConfig();
            c.setId(1L);
            return repo.save(c);
        });
    }

    public boolean available() {
        AiConfig c = get();
        return c.isEnabled() && c.getBaseUrl() != null && !c.getBaseUrl().isBlank();
    }

    public boolean isMock() {
        String b = get().getBaseUrl();
        return b != null && b.startsWith("mock://");
    }

    /** 有效单次超时（秒）：给推理型模型留足时间，同时设下限防止误配过小。 */
    public int effectiveTimeoutSeconds() {
        return Math.max(get().getTimeoutSeconds(), 45);
    }

    /** 有效重试次数：不再强制下限（空内容已由 reasoning_content 兜底），交由配置决定，避免最坏耗时被成倍放大。 */
    public int effectiveRetries() {
        return Math.max(get().getMaxRetries(), 0);
    }

    /** 调用 LLM，受并发限制；失败抛异常由上层降级。 */
    public String chat(List<AiProvider.Message> messages) throws Exception {
        AiConfig c = get();
        AiProvider provider = isMock() ? mock : openAi;
        // 推理型模型（如 deepseek-flash）会把思考放进 reasoning_content 并需要更多 token/时间，
        // 这里给足预算；超时/重试的有效值同时供对局看门狗使用，二者保持一致，避免看门狗抢先把 AI 结果丢弃。
        // 推理型模型会先产出较长的 reasoning 再给出最终 JSON/文本，token 预算过小会把答案截断成
        // "{" 之类导致解析失败、频繁降级随机。这里为各档都留足预算。
        int maxTokens = switch (c.getDepth() == null ? "medium" : c.getDepth()) {
            case "low" -> 2048;
            case "high" -> 8192;
            default -> 4096;
        };
        // tokenBudgetPerGame：单局 token 预算上限，用于约束单次请求的 max_tokens，
        // 避免一次请求就吃掉整局预算（预算更小时以预算为准）。配置项由此生效，不再是死配置。
        long gameBudget = c.getTokenBudgetPerGame();
        if (gameBudget > 0 && gameBudget < maxTokens) {
            log.warn("tokenBudgetPerGame={} 小于本档 maxTokens={}，将单次请求 max_tokens 收敛到预算", gameBudget, maxTokens);
            maxTokens = (int) gameBudget;
        }
        int timeout = effectiveTimeoutSeconds();
        int retries = effectiveRetries();
        AiProvider.AiOptions opts = new AiProvider.AiOptions(
                c.getBaseUrl(), CryptoUtil.decrypt(c.getApiKeyEnc()), c.getModel(),
                c.getTemperature(), maxTokens, timeout, retries);
        Semaphore s = ensureSemaphore(c.getConcurrency());
        // 限并发获取改为带超时：并发槽位被长期占用时不再无限阻塞对局线程，
        // 超时即放弃本次调用（tryAcquire 失败未获得许可，无需释放），由上层降级为随机/默认动作。
        boolean acquired;
        try {
            acquired = s.tryAcquire(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("获取 AI 并发槽位被中断");
        }
        if (!acquired) {
            throw new RuntimeException("获取 AI 并发槽位超时(>" + timeout + "s)，降级为随机/默认动作");
        }
        try {
            return provider.chat(messages, opts);
        } finally {
            s.release();
        }
    }

    private synchronized Semaphore ensureSemaphore(int size) {
        if (size < 1) size = 1;
        if (semaphore == null || semSize != size) {
            semaphore = new Semaphore(size);
            semSize = size;
        }
        return semaphore;
    }

    /* ---------- 配置读写 ---------- */

    public record ConfigDto(String baseUrl, String apiKey, String model, String depth,
                            Double temperature, Integer timeoutSeconds, Integer maxRetries,
                            Integer concurrency, Long tokenBudgetPerGame, Boolean enabled) {}

    public AiConfig saveConfig(ConfigDto dto) {
        AiConfig c = get();
        if (dto.baseUrl() != null) c.setBaseUrl(dto.baseUrl().trim());
        if (dto.apiKey() != null && !dto.apiKey().isBlank()) c.setApiKeyEnc(CryptoUtil.encrypt(dto.apiKey().trim()));
        if (dto.model() != null) c.setModel(dto.model().trim());
        if (dto.depth() != null) c.setDepth(dto.depth());
        if (dto.temperature() != null) c.setTemperature(dto.temperature());
        if (dto.timeoutSeconds() != null) c.setTimeoutSeconds(dto.timeoutSeconds());
        if (dto.maxRetries() != null) c.setMaxRetries(dto.maxRetries());
        if (dto.concurrency() != null) c.setConcurrency(dto.concurrency());
        if (dto.tokenBudgetPerGame() != null) c.setTokenBudgetPerGame(dto.tokenBudgetPerGame());
        if (dto.enabled() != null) c.setEnabled(dto.enabled());
        AiConfig saved = repo.save(c);
        log.info("AI 配置更新 enabled={} baseUrl={} model={} depth={}", saved.isEnabled(), saved.getBaseUrl(), saved.getModel(), saved.getDepth());
        return saved;
    }

    /** 返回脱敏配置（不含明文 key）。 */
    public java.util.Map<String, Object> publicConfig() {
        AiConfig c = get();
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("baseUrl", c.getBaseUrl());
        m.put("hasKey", c.getApiKeyEnc() != null && !c.getApiKeyEnc().isBlank());
        m.put("model", c.getModel());
        m.put("depth", c.getDepth());
        m.put("temperature", c.getTemperature());
        m.put("timeoutSeconds", c.getTimeoutSeconds());
        m.put("maxRetries", c.getMaxRetries());
        m.put("concurrency", c.getConcurrency());
        m.put("tokenBudgetPerGame", c.getTokenBudgetPerGame());
        m.put("enabled", c.isEnabled());
        m.put("mock", isMock());
        return m;
    }
}
