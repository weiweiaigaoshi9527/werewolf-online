package com.werewolf.service;

import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线状态：userId -> WebSocketSession。
 * 同一 userId 只保留最新连接（旧连接被顶下线时由 WS handler 主动关闭）。
 */
@Service
public class PresenceService {

    private final Map<Long, WebSocketSession> online = new ConcurrentHashMap<>();

    public WebSocketSession register(long userId, WebSocketSession session) {
        return online.put(userId, session);
    }

    /** 仅当当前记录就是这条 session 时才移除，避免顶号误踢新连接 */
    public void unregister(long userId, WebSocketSession session) {
        online.remove(userId, session);
    }

    public boolean isOnline(long userId) {
        return online.containsKey(userId);
    }

    public Optional<WebSocketSession> sessionOf(long userId) {
        return Optional.ofNullable(online.get(userId));
    }

    public Map<Long, WebSocketSession> snapshot() {
        return Map.copyOf(online);
    }
}
