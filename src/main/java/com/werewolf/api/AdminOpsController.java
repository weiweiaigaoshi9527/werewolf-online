package com.werewolf.api;

import com.werewolf.game.LiveGame;
import com.werewolf.game.LiveGameService;
import com.werewolf.model.Room;
import com.werewolf.model.User;
import com.werewolf.repo.ForumPostRepository;
import com.werewolf.repo.UserRepository;
import com.werewolf.service.PresenceService;
import com.werewolf.service.RoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 后台·运维工具（仅管理员，/api/admin/** 已被拦截器保护）。 */
@RestController
@RequestMapping("/api/admin/ops")
public class AdminOpsController {

    private final UserRepository users;
    private final PresenceService presence;
    private final LiveGameService gameService;
    private final ForumPostRepository forumPosts;
    private final RoomService roomService;

    public AdminOpsController(UserRepository users, PresenceService presence, LiveGameService gameService,
                              ForumPostRepository forumPosts, RoomService roomService) {
        this.users = users;
        this.presence = presence;
        this.gameService = gameService;
        this.forumPosts = forumPosts;
        this.roomService = roomService;
    }

    /** 论坛全部帖子（含已隐藏），供管理员审核。 */
    @GetMapping("/forum")
    public ResponseEntity<?> forumAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var p : forumPosts.findAllByOrderByIdDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId()); m.put("title", p.getTitle());
            m.put("author", users.findById(p.getUserId()).map(User::getNickname).orElse("#" + p.getUserId()));
            m.put("category", p.getCategory()); m.put("pinned", p.isPinned()); m.put("hidden", p.isHidden());
            m.put("replyCount", p.getReplyCount()); m.put("at", p.getCreatedAt() == null ? null : p.getCreatedAt().toString());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /** 当前在线用户列表 + 每人“现在在干什么”（大厅 / 房间等待 / 对局中·是否轮到TA / 观战）。 */
    @GetMapping("/online")
    public ResponseEntity<?> online() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Long uid : presence.snapshot().keySet()) {
            users.findById(uid).ifPresent(u -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", u.getId()); m.put("username", u.getUsername());
                m.put("nickname", u.getNickname()); m.put("level", u.getLevel()); m.put("admin", u.isAdmin());
                String[] act = activityOf(uid);
                m.put("activity", act[0]); m.put("roomNo", act[1]);
                list.add(m);
            });
        }
        list.sort(Comparator.comparing(x -> String.valueOf(((Map) x).get("activity"))));
        return ResponseEntity.ok(Map.of("count", list.size(), "users", list));
    }

    /** 计算某在线用户当前所处：返回 [状态文案, 房号或空]。 */
    private String[] activityOf(long uid) {
        Optional<Room> roomOpt = roomService.findRoomOfUser(uid);
        if (roomOpt.isEmpty()) return new String[]{"大厅", ""};
        Room room = roomOpt.get();
        String no = room.getRoomNo();
        if ("PLAYING".equals(room.getStatus())) {
            LiveGame lg = gameService.get(room.getId());
            if (lg != null) {
                Integer seat = lg.userToSeat.get(uid);
                if (seat != null) {
                    boolean acting = lg.engine.currentActor() == seat;
                    return new String[]{acting ? ("对局中 · 轮到TA行动") : ("对局中 · " + seat + "号"), no};
                }
                return new String[]{"观战中", no};
            }
            return new String[]{"房间 " + no, no};
        }
        boolean host = room.getHostUserId() != null && room.getHostUserId() == uid;
        return new String[]{host ? ("房间 " + no + " · 房主等待开局") : ("房间 " + no + " · 等待开局"), no};
    }

    /** 导出全部用户为 CSV（UTF-8 BOM，Excel 友好）。 */
    @GetMapping("/export/users")
    public ResponseEntity<byte[]> exportUsers() {
        StringBuilder sb = new StringBuilder("id,username,nickname,level,exp,gold,admin,banned,vipLevel,createdAt\n");
        for (User u : users.findAll()) {
            sb.append(u.getId()).append(',')
              .append(csv(u.getUsername())).append(',').append(csv(u.getNickname())).append(',')
              .append(u.getLevel()).append(',').append(u.getExp()).append(',').append(u.getGold()).append(',')
              .append(u.isAdmin()).append(',').append(u.isBanned()).append(',')
              .append(u.getVipLevel()).append(',')
              .append(csv(u.getCreatedAt() == null ? "" : u.getCreatedAt().toString())).append('\n');
        }
        byte[] body = withBom(sb.toString());
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"users.csv\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    /** 强制结束所有进行中的对局（复位所有房间）。 */
    @PostMapping("/end-all")
    public ResponseEntity<?> endAll() {
        int n = gameService.forceEndAll();
        return ResponseEntity.ok(Map.of("ok", true, "ended", n));
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
    private static byte[] withBom(String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[b.length + 3];
        out[0] = (byte) 0xEF; out[1] = (byte) 0xBB; out[2] = (byte) 0xBF;
        System.arraycopy(b, 0, out, 3, b.length);
        return out;
    }
}
