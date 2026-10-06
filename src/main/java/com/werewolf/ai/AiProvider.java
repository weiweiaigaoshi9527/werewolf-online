package com.werewolf.ai;

import java.util.List;

/** LLM 提供方抽象。 */
public interface AiProvider {
    record Message(String role, String content) {}

    /** 返回助手回复文本；失败抛异常由上层降级。 */
    String chat(List<Message> messages, AiOptions opts) throws Exception;

    record AiOptions(String baseUrl, String apiKey, String model, double temperature,
                     int maxTokens, int timeoutSeconds, int maxRetries) {}
}
