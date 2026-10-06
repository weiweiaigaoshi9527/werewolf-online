package com.werewolf.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.werewolf.service.PresenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/** 向指定在线用户的 /ws 连接推送一条 JSON 消息（好友申请、私聊、房间邀请等实时通知）。 */
@Component
public class WsPush {

    private static final Logger log = LoggerFactory.getLogger(WsPush.class);
    private final PresenceService presence;
    private final ObjectMapper mapper = new ObjectMapper();

    public WsPush(PresenceService presence) {
        this.presence = presence;
    }

    public void send(long userId, String type, Map<String, ?> payload) {
        presence.sessionOf(userId).ifPresent(s -> send(s, type, payload));
    }

    public void send(WebSocketSession s, String type, Map<String, ?> payload) {
        if (s == null || !s.isOpen()) return;
        try {
            ObjectNode root = mapper.createObjectNode();
            if (payload != null) payload.forEach(root::putPOJO);
            root.put("type", type); // 最后写入，确保信封 type 不被 payload 里的同名字段覆盖
            synchronized (s) { s.sendMessage(new TextMessage(mapper.writeValueAsString(root))); }
        } catch (Exception e) {
            log.debug("WS 推送失败 type={}: {}", type, e.getMessage());
        }
    }
}
