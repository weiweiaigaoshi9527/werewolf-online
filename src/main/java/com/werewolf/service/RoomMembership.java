package com.werewolf.service;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 房间成员/观战内存索引：roomId -> 在线 userId 集合（座位玩家 + 观战者）。
 * 用于广播路由，避免每次查库。
 */
@Service
public class RoomMembership {

    private final Map<Long, Set<Long>> members = new ConcurrentHashMap<>();

    public void add(long roomId, long userId) {
        members.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(userId);
    }

    public void remove(long roomId, long userId) {
        Set<Long> s = members.get(roomId);
        if (s != null) {
            s.remove(userId);
            if (s.isEmpty()) members.remove(roomId);
        }
    }

    /** 用户离开当前房间时调用：从所有房间集合中移除（他最多在一个房间里） */
    public void removeEverywhere(long userId) {
        members.values().forEach(s -> s.remove(userId));
        members.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    public Set<Long> membersOf(long roomId) {
        return Collections.unmodifiableSet(members.getOrDefault(roomId, Set.of()));
    }

    public void clearRoom(long roomId) {
        members.remove(roomId);
    }
}
