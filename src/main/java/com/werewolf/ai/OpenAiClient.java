package com.werewolf.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** OpenAI 兼容 Chat Completions 客户端（真实调用）。 */
public class OpenAiClient implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** 可重试的瞬态 HTTP 状态码（其余 4xx/鉴权类错误一律不重试）。 */
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 409, 425, 429, 500, 502, 503, 504, 529);
    /** Full Jitter 退避：base=500ms、cap=8000ms。 */
    private static final long BACKOFF_BASE_MS = 500L;
    private static final long BACKOFF_CAP_MS = 8000L;
    /** 采用 Retry-After 时的钳制上限（10s）。 */
    private static final long RETRY_AFTER_MAX_MS = 10_000L;

    @Override
    public String chat(List<Message> messages, AiOptions o) throws Exception {
        String url = normalizeBase(o.baseUrl()) + "/chat/completions";
        ObjectNode body = mapper.createObjectNode();
        body.put("model", o.model());
        body.put("temperature", o.temperature());
        if (o.maxTokens() > 0) body.put("max_tokens", o.maxTokens());
        ArrayNode arr = body.putArray("messages");
        for (Message m : messages) {
            ObjectNode mn = arr.addObject();
            mn.put("role", m.role());
            mn.put("content", m.content());
        }
        String json = mapper.writeValueAsString(body);

        Exception last = null;
        for (int attempt = 0; attempt <= o.maxRetries(); attempt++) {
            // 记录本次尝试的起始时间，用于失败日志中输出耗时；不记录 api key 与 prompt 内容。
            long startNanos = System.nanoTime();
            Integer status = null;      // 本轮拿到的 HTTP 状态码；未拿到响应（连接/超时）时为 null
            String retryAfter = null;   // 响应头 Retry-After（若有）
            String respBody = null;     // 响应体原文，仅用于错误分类，不写入日志
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(o.timeoutSeconds()))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + (o.apiKey() == null ? "" : o.apiKey()))
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
                // 用异步 + 硬超时兜底：即便底层 .timeout() 因连接停滞未触发，也能在 timeout+3s 内强制返回并释放线程，
                // 避免 aiAct 线程被永久挂起（cached 线程池会因此无限泄漏线程）。
                CompletableFuture<HttpResponse<String>> fut =
                        http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                HttpResponse<String> resp;
                try {
                    resp = fut.get(o.timeoutSeconds() + 3L, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    fut.cancel(true);
                    throw new RuntimeException("请求超时 >" + (o.timeoutSeconds() + 3) + "s");
                }
                status = resp.statusCode();
                retryAfter = resp.headers().firstValue("Retry-After").orElse(null);
                respBody = resp.body();
                if (status / 100 != 2) {
                    throw new RuntimeException("HTTP " + status + ": " + truncate(respBody));
                }
                JsonNode root = mapper.readTree(respBody);
                JsonNode msg = root.path("choices").path(0).path("message");
                String content = stripThinking(msg.path("content").asText(""));
                if (content.isBlank()) {
                    // 推理型模型可能把最终答复放在 content 之外；退回 reasoning_content 兜底。
                    // 注意：兜底内容可能是模型的“思考过程”，调用方（AiPolicy/SpeechSanitizer）会再做清洗与拦截。
                    content = stripThinking(msg.path("reasoning_content").asText(""));
                }
                if (content.isBlank()) {
                    throw new RuntimeException("响应无内容: " + truncate(respBody));
                }
                return content.trim();
            } catch (Exception e) {
                last = e;
                // 失败重试日志：补充模型名、目标 URL、尝试次数、耗时与消息条数；严禁记录 api key 与完整 prompt。
                long costMs = (System.nanoTime() - startNanos) / 1_000_000L;
                log.warn("AI 调用失败 模型={} url={} 第{}次 耗时={}ms 消息数={} 错误={}",
                        o.model(), url, attempt + 1, costMs, messages.size(), e.getMessage());
                // 分类重试：非瞬态（请求/鉴权类）错误立即失败，不再重试，并明确打一条 warn。
                if (!isRetryable(status, respBody, e)) {
                    log.warn("AI 调用错误不可重试，立即放弃 模型={} url={} 第{}次 status={} 错误={}",
                            o.model(), url, attempt + 1, status, e.getMessage());
                    break;
                }
                if (attempt < o.maxRetries()) {
                    long delayMs = computeDelay(attempt, retryAfter);
                    try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
                }
            }
        }
        throw last != null ? last : new RuntimeException("AI 调用失败");
    }

    /**
     * 判定本次失败是否属于可重试的瞬态错误。
     *
     * @param status HTTP 状态码；未拿到响应时为 null
     * @param body   响应体原文（用于关键字识别，可能为 null）
     * @param e      本次抛出的异常
     */
    private static boolean isRetryable(Integer status, String body, Throwable e) {
        if (status != null) {
            // 已拿到 HTTP 响应：2xx 之后的解析/空内容异常视为瞬态，保留既有重试行为。
            if (status / 100 == 2) return true;
            if (RETRYABLE_STATUS.contains(status)) return true;
            if (containsTransientKeyword(body)) return true;
            // 其余状态（400/401/403/404/422 等请求/鉴权类错误）不重试。
            return false;
        }
        // 未拿到响应：仅连接/IO/超时类异常才重试。
        return isTransientException(e);
    }

    /** 响应体（大小写不敏感）是否含服务端瞬态错误关键字。 */
    private static boolean containsTransientKeyword(String body) {
        if (body == null || body.isEmpty()) return false;
        String b = body.toLowerCase(Locale.ROOT);
        return b.contains("no_healthy_account")
                || b.contains("rate_limit_exceeded")
                || b.contains("temporarily unavailable");
    }

    /** 异常链中是否含超时/连接/IO 类瞬态异常。 */
    private static boolean isTransientException(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof TimeoutException) return true;
            if (t instanceof IOException) return true;
        }
        // 硬超时兜底抛出的是无 IO 异常链的 RuntimeException，按消息兜底识别。
        String msg = e == null ? null : e.getMessage();
        return msg != null && msg.contains("请求超时");
    }

    /**
     * 计算下一次重试前的等待时间（毫秒）。
     * 优先采用 Retry-After（秒数或 HTTP 日期，钳制在 10s 内）；否则使用 Full Jitter：
     * delay = random(0, min(cap, base * 2^attempt))，base=500ms、cap=8000ms。
     */
    private static long computeDelay(int attempt, String retryAfterHeader) {
        long fromHeader = parseRetryAfterMillis(retryAfterHeader);
        if (fromHeader >= 0) return fromHeader;
        int exp = Math.min(Math.max(attempt, 0), 30);
        long bound = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * (1L << exp));
        return ThreadLocalRandom.current().nextLong(bound + 1);
    }

    /** 解析 Retry-After：支持“秒数”与“HTTP 日期”，返回毫秒并钳制在 10s 内；无法解析返回 -1。 */
    private static long parseRetryAfterMillis(String header) {
        if (header == null) return -1;
        String h = header.trim();
        if (h.isEmpty()) return -1;
        try {
            long seconds = Long.parseLong(h);
            if (seconds < 0) seconds = 0;
            return Math.min(seconds * 1000L, RETRY_AFTER_MAX_MS);
        } catch (NumberFormatException ignore) {
            // 非秒数，继续按 HTTP 日期解析
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(h, DateTimeFormatter.RFC_1123_DATE_TIME);
            long millis = Duration.between(Instant.now(), when.toInstant()).toMillis();
            if (millis < 0) millis = 0;
            return Math.min(millis, RETRY_AFTER_MAX_MS);
        } catch (DateTimeParseException ignore) {
            return -1;
        }
    }

    /** 剥离模型内联的思考标签，避免把推理过程当作最终答复返回（不动代码围栏，动作 JSON 可能在其中）。 */
    private static String stripThinking(String s) {
        if (s == null) return "";
        String out = s;
        out = out.replaceAll("(?is)<\\s*(think|thinking|reasoning|analysis|scratchpad|thought)\\s*>.*?<\\s*/\\s*\\1\\s*>", " ");
        out = out.replaceAll("(?is)<\\s*(think|thinking|reasoning|analysis|scratchpad|thought)\\s*>.*$", " ");
        out = out.replaceAll("(?s)【(思考|思路|内心|推理|分析)】.*?(?=\\n|$)", " ");
        return out.trim();
    }

    private String normalizeBase(String base) {
        if (base == null || base.isBlank()) throw new IllegalArgumentException("baseUrl 未配置");
        String b = base.trim();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
