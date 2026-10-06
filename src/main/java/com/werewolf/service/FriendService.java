package com.werewolf.service;

import com.werewolf.model.FriendRequest;
import com.werewolf.model.Friendship;
import com.werewolf.model.PrivateMessage;
import com.werewolf.model.User;
import com.werewolf.repo.FriendRequestRepository;
import com.werewolf.repo.FriendshipRepository;
import com.werewolf.repo.PrivateMessageRepository;
import com.werewolf.repo.UserRepository;
import com.werewolf.ws.WsPush;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 好友系统：申请/添加、私聊、房间邀请、查看档案、在线状态。实时通知走 WsPush。 */
@Service
public class FriendService {

    private final UserRepository users;
    private final FriendshipRepository friends;
    private final FriendRequestRepository requests;
    private final PrivateMessageRepository messages;
    private final PresenceService presence;
    private final DecorationService decoration;
    private final WsPush push;
    private final com.werewolf.repo.MatchParticipantRepository matches;

    public FriendService(UserRepository users, FriendshipRepository friends, FriendRequestRepository requests,
                         PrivateMessageRepository messages, PresenceService presence,
                         DecorationService decoration, WsPush push, com.werewolf.repo.MatchParticipantRepository matches) {
        this.users = users;
        this.friends = friends;
        this.requests = requests;
        this.messages = messages;
        this.presence = presence;
        this.decoration = decoration;
        this.push = push;
        this.matches = matches;
    }

    private Map<String, Object> summary(User u) {
        return summary(u, decoration.of(u));
    }

    /** 用已批量解析好的装饰信息组装用户摘要，避免逐条查询。 */
    private Map<String, Object> summary(User u, Map<String, Object> dec) {
        if (dec == null) dec = decoration.of(u);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("nickname", u.getNickname());
        m.put("avatarId", u.getAvatarId());
        m.put("avatarUrl", dec.get("avatarUrl"));
        m.put("nickColor", dec.get("nickColor"));
        m.put("frameColor", dec.get("frameColor"));
        m.put("title", dec.get("title"));
        m.put("level", u.getLevel());
        m.put("online", presence.isOnline(u.getId()));
        m.put("banned", u.isBanned());
        return m;
    }

    @Transactional
    public List<Map<String, Object>> listFriends(long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Friendship> fs = friends.findByUserId(userId);
        if (fs.isEmpty()) return out;

        // 批量取出所有好友：一次 findAllById，替代循环内逐个 findById
        List<Long> friendIds = new ArrayList<>();
        for (Friendship f : fs) friendIds.add(f.getFriendId());
        Map<Long, User> userMap = new HashMap<>();
        for (User u : users.findAllById(friendIds)) userMap.put(u.getId(), u);
        List<User> friendUsers = new ArrayList<>();
        for (Long fid : friendIds) {
            User u = userMap.get(fid);
            if (u != null) friendUsers.add(u);
        }
        if (friendUsers.isEmpty()) return out;

        // 批量解析装饰（一次 findAllById 查出所有头像框/称号）
        Map<Long, Map<String, Object>> decMap = decoration.ofAll(friendUsers);

        // 一次性按来源聚合未读数：fromId -> count，替代对每个好友分别统计
        Map<Long, Long> unreadMap = new HashMap<>();
        for (Object[] row : messages.countUnreadGroupByFrom(userId)) {
            if (row[0] == null) continue;
            unreadMap.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }

        for (User u : friendUsers) {
            Map<String, Object> m = summary(u, decMap.get(u.getId()));
            m.put("unread", unreadMap.getOrDefault(u.getId(), 0L));
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> ((Map<String, Object>) m).get("nickname") == null ? "" : m.get("nickname").toString()));
        return out;
    }

    /** 单个会话未读数（供其他场景按需调用），直接走聚合计数查询而非加载整段会话。 */
    private long unreadFor(long me, long peer) {
        return messages.countByToIdAndFromIdAndReadByToFalse(me, peer);
    }

    public List<Map<String, Object>> searchUsers(long userId, String kw) {
        if (kw == null || kw.isBlank()) return List.of();
        String k = kw.trim().toLowerCase();
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : users.findAll()) {
            if (u.getId().equals(userId)) continue;
            if (!(u.getUsername().toLowerCase().contains(k) || u.getNickname().toLowerCase().contains(k))) continue;
            Map<String, Object> m = summary(u);
            m.put("isFriend", friends.existsByUserIdAndFriendId(userId, u.getId()));
            m.put("pending", requests.findByFromIdAndToIdAndStatus(userId, u.getId(), "PENDING").isPresent()
                    || requests.findByFromIdAndToIdAndStatus(u.getId(), userId, "PENDING").isPresent());
            out.add(m);
            if (out.size() >= 20) break;
        }
        return out;
    }

    @Transactional
    public String sendRequest(long fromId, long toId, String greeting) {
        if (fromId == toId) return "不能添加自己";
        Optional<User> to = users.findById(toId);
        if (to.isEmpty()) return "用户不存在";
        if (to.get().isBanned()) return "无法添加该用户";
        if (friends.existsByUserIdAndFriendId(fromId, toId)) return "你们已经是好友了";
        if (requests.findByFromIdAndToIdAndStatus(fromId, toId, "PENDING").isPresent()) return "已发送申请，等待对方处理";
        if (requests.findByFromIdAndToIdAndStatus(toId, fromId, "PENDING").isPresent()) {
            // 对方已申请过我：直接互相成为好友
            FriendRequest rev = requests.findByFromIdAndToIdAndStatus(toId, fromId, "PENDING").get();
            rev.setStatus("ACCEPTED");
            requests.save(rev);
            linkBoth(fromId, toId);
            pushUser(toId, "friend.accepted", fromId);
            return null; // 成功（直接成为好友）
        }
        FriendRequest r = new FriendRequest();
        r.setFromId(fromId);
        r.setToId(toId);
        r.setStatus("PENDING");
        r.setGreeting(greeting == null || greeting.isBlank() ? null : greeting.trim());
        requests.save(r);
        users.findById(fromId).ifPresent(from ->
                push.send(toId, "friend.request", Map.of("from", summary(from), "reqId", r.getId(), "greeting", r.getGreeting() == null ? "" : r.getGreeting())));
        return null;
    }

    @Transactional
    public List<Map<String, Object>> incomingRequests(long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FriendRequest r : requests.findByToIdAndStatusOrderByCreatedAtDesc(userId, "PENDING")) {
            users.findById(r.getFromId()).ifPresent(from -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("reqId", r.getId());
                m.put("from", summary(from));
                m.put("greeting", r.getGreeting() == null ? "" : r.getGreeting());
                m.put("at", r.getCreatedAt().toString());
                out.add(m);
            });
        }
        return out;
    }

    @Transactional
    public String acceptRequest(long reqId, long userId) {
        Optional<FriendRequest> r = requests.findById(reqId);
        if (r.isEmpty() || !r.get().getToId().equals(userId) || !"PENDING".equals(r.get().getStatus()))
            return "申请无效或已处理";
        FriendRequest req = r.get();
        req.setStatus("ACCEPTED");
        requests.save(req);
        linkBoth(req.getFromId(), req.getToId());
        pushUser(req.getToId(), "friend.accepted", req.getFromId());
        pushUser(req.getFromId(), "friend.accepted", req.getToId());
        return null;
    }

    @Transactional
    public String rejectRequest(long reqId, long userId) {
        Optional<FriendRequest> r = requests.findById(reqId);
        if (r.isEmpty() || !r.get().getToId().equals(userId)) return "申请无效";
        r.get().setStatus("REJECTED");
        requests.save(r.get());
        return null;
    }

    @Transactional
    public void removeFriend(long userId, long friendId) {
        friends.findByUserIdAndFriendId(userId, friendId).ifPresent(friends::delete);
        friends.findByUserIdAndFriendId(friendId, userId).ifPresent(friends::delete);
    }

    private void linkBoth(long a, long b) {
        if (!friends.existsByUserIdAndFriendId(a, b)) {
            Friendship f1 = new Friendship(); f1.setUserId(a); f1.setFriendId(b); friends.save(f1);
        }
        if (!friends.existsByUserIdAndFriendId(b, a)) {
            Friendship f2 = new Friendship(); f2.setUserId(b); f2.setFriendId(a); friends.save(f2);
        }
    }

    private void pushUser(long toUserId, String type, long otherId) {
        users.findById(otherId).ifPresent(u -> push.send(toUserId, type, Map.of("user", summary(u))));
    }

    @Transactional
    public List<Map<String, Object>> conversation(long userId, long peerId) {
        messages.markRead(userId, peerId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (PrivateMessage m : messages.findConversation(userId, peerId)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", m.getId());
            x.put("from", m.getFromId());
            x.put("to", m.getToId());
            x.put("type", m.getType());
            x.put("content", m.getContent());
            x.put("roomNo", m.getRoomNo());
            x.put("mine", m.getFromId().equals(userId));
            x.put("at", m.getCreatedAt().toString());
            out.add(x);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> sendMessage(long fromId, long toId, String content, String type, String roomNo) {
        // 非好友不能私聊：直接返回带 error 的结果（保持返回类型不变，由前端/调用方识别）
        if (!friends.existsByUserIdAndFriendId(fromId, toId)) {
            Map<String, Object> deny = new LinkedHashMap<>();
            deny.put("error", "非好友不能私聊");
            return deny;
        }
        PrivateMessage m = new PrivateMessage();
        m.setFromId(fromId);
        m.setToId(toId);
        m.setType(type == null ? "chat" : type);
        m.setContent(content);
        m.setRoomNo(roomNo);
        final PrivateMessage saved = messages.save(m);
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("id", saved.getId()); x.put("from", fromId); x.put("to", toId);
        x.put("type", saved.getType()); x.put("content", content); x.put("roomNo", roomNo);
        x.put("mine", false); x.put("at", saved.getCreatedAt().toString());
        users.findById(fromId).ifPresent(from -> {
            Map<String, Object> p = new LinkedHashMap<>(x);
            p.put("fromName", from.getNickname());
            if ("invite".equals(saved.getType())) push.send(toId, "room.invite", p);
            else push.send(toId, "chat.msg", p);
        });
        return x;
    }

    public Map<String, Object> unread(long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("messages", messages.countByToIdAndReadByToFalse(userId));
        m.put("requests", requests.countByToIdAndStatus(userId, "PENDING"));
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> profile(long requesterId, long targetId) {
        // AI 机器人（负数 id）不提供档案
        if (targetId < 0) return null;
        Optional<User> u = users.findById(targetId);
        if (u.isEmpty()) return null;
        Map<String, Object> m = summary(u.get());
        // 隐私控制：非本人且非好友时只返回公开信息（昵称/等级/头像/装扮），隐藏敏感字段
        boolean self = requesterId == targetId;
        boolean isFriend = friends.existsByUserIdAndFriendId(requesterId, targetId);
        if (!self && !isFriend) {
            return m;
        }
        m.put("exp", u.get().getExp());
        m.put("gold", u.get().getGold());
        m.put("birthday", u.get().getBirthday());
        m.put("location", u.get().getLocation());
        m.put("regLocation", u.get().getRegLocation());
        m.put("signature", u.get().getSignature());
        m.put("createdAt", u.get().getCreatedAt() == null ? null : u.get().getCreatedAt().toString());
        m.put("lastOnlineAt", u.get().getLastOnlineAt() == null ? null : u.get().getLastOnlineAt().toString());
        m.put("onlineSeconds", u.get().getOnlineSeconds());
        // 详细战绩（供好友档案展示）
        int total = 0, win = 0, wolfN = 0, wolfW = 0, goodN = 0, goodW = 0, mvp = 0;
        for (var mp : matches.findByUserIdOrderByIdDesc(targetId)) {
            total++; if (mp.isWon()) win++; if (mp.isMvp()) mvp++;
            if ("WOLF".equals(mp.getFaction())) { wolfN++; if (mp.isWon()) wolfW++; }
            else { goodN++; if (mp.isWon()) goodW++; }
        }
        m.put("games", total);
        m.put("winRate", total == 0 ? 0 : Math.round(win * 1000.0 / total) / 10.0);
        m.put("wolfGames", wolfN);
        m.put("wolfWinRate", wolfN == 0 ? 0 : Math.round(wolfW * 1000.0 / wolfN) / 10.0);
        m.put("goodGames", goodN);
        m.put("goodWinRate", goodN == 0 ? 0 : Math.round(goodW * 1000.0 / goodN) / 10.0);
        m.put("mvpCount", mvp);
        return m;
    }

    /** 机器人随机档案：用其（负数）userId 作种子，稳定生成生日/所在地/在线等，避免每次刷新都变。 */
    private Map<String, Object> botProfile(long botId) {
        Random r = new Random(botId * 2654435761L);
        String[] surnames = {"李", "王", "张", "刘", "陈", "杨", "赵", "黄", "周", "吴"};
        String[] cities = {"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "西安", "南京", "重庆", "长沙", "青岛"};
        String[] provinces = {"广东", "江苏", "浙江", "四川", "湖北", "山东", "陕西", "北京", "上海"};
        int year = 1988 + r.nextInt(15), month = 1 + r.nextInt(12), day = 1 + r.nextInt(28);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", botId);
        m.put("username", "bot_" + Math.abs(botId));
        m.put("nickname", surnames[r.nextInt(surnames.length)] + "同学");
        m.put("avatarId", 1 + r.nextInt(16));
        m.put("avatarUrl", null);
        m.put("nickColor", null);
        m.put("frameColor", null);
        m.put("title", null);
        m.put("level", 1 + r.nextInt(30));
        m.put("online", false);
        m.put("banned", false);
        m.put("birthday", String.format("%04d-%02d-%02d", year, month, day));
        m.put("location", cities[r.nextInt(cities.length)]);
        m.put("regLocation", provinces[r.nextInt(provinces.length)]);
        m.put("createdAt", java.time.LocalDateTime.now().minusDays(30 + r.nextInt(700)).toString());
        m.put("lastOnlineAt", java.time.LocalDateTime.now().minusMinutes(r.nextInt(1440)).toString());
        m.put("onlineSeconds", r.nextInt(200) * 3600L / 10);
        return m;
    }
}
