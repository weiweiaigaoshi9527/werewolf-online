package com.werewolf.api;

import com.werewolf.model.GoldTransaction;
import com.werewolf.model.User;
import com.werewolf.model.UserTicket;
import com.werewolf.repo.FriendRequestRepository;
import com.werewolf.repo.GameKudosRepository;
import com.werewolf.repo.FriendshipRepository;
import com.werewolf.repo.GoldTransactionRepository;
import com.werewolf.repo.InventoryRepository;
import com.werewolf.repo.MatchParticipantRepository;
import com.werewolf.repo.PrivateMessageRepository;
import com.werewolf.repo.RedeemUsageRepository;
import com.werewolf.repo.RoleStatRepository;
import com.werewolf.repo.RoomPlayerRepository;
import com.werewolf.repo.TicketReplyRepository;
import com.werewolf.repo.UserRepository;
import com.werewolf.repo.UserTicketRepository;
import com.werewolf.service.AuthService;
import com.werewolf.service.PresenceService;
import com.werewolf.service.VipService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 后台·用户管理（仅管理员，鉴权由 AdminInterceptor 统一处理）。 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    /** 管理操作审计日志：记录操作者与目标，便于安全审计（严禁记录密码/token 明文）。 */
    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);

    private final UserRepository users;
    private final PresenceService presence;
    private final AuthService authService;
    private final RoomPlayerRepository roomPlayers;
    private final FriendshipRepository friendships;
    private final FriendRequestRepository friendRequests;
    private final PrivateMessageRepository messages;
    private final VipService vipService;
    private final GoldTransactionRepository goldRepo;
    // 级联删除所需的其余仓库
    private final MatchParticipantRepository matchParticipants;
    private final RoleStatRepository roleStats;
    private final InventoryRepository inventories;
    private final RedeemUsageRepository redeemUsages;
    private final UserTicketRepository userTickets;
    private final TicketReplyRepository ticketReplies;
    private final GameKudosRepository gameKudos;

    public AdminUserController(UserRepository users, PresenceService presence, AuthService authService,
                               RoomPlayerRepository roomPlayers, FriendshipRepository friendships,
                               FriendRequestRepository friendRequests, PrivateMessageRepository messages,
                               VipService vipService, GoldTransactionRepository goldRepo,
                               MatchParticipantRepository matchParticipants, RoleStatRepository roleStats,
                               InventoryRepository inventories, RedeemUsageRepository redeemUsages,
                               UserTicketRepository userTickets, TicketReplyRepository ticketReplies,
                               GameKudosRepository gameKudos) {
        this.users = users;
        this.presence = presence;
        this.authService = authService;
        this.roomPlayers = roomPlayers;
        this.friendships = friendships;
        this.friendRequests = friendRequests;
        this.messages = messages;
        this.vipService = vipService;
        this.goldRepo = goldRepo;
        this.matchParticipants = matchParticipants;
        this.roleStats = roleStats;
        this.inventories = inventories;
        this.redeemUsages = redeemUsages;
        this.userTickets = userTickets;
        this.ticketReplies = ticketReplies;
        this.gameKudos = gameKudos;
    }

    public record FlagBody(boolean value) {}
    public record PwBody(String password) {}

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestParam(value = "kw", required = false) String kw) {
        String k = kw == null ? "" : kw.trim().toLowerCase();
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : users.findAll()) {
            if (!k.isEmpty() && !(u.getUsername().toLowerCase().contains(k) || u.getNickname().toLowerCase().contains(k)))
                continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("nickname", u.getNickname());
            m.put("level", u.getLevel());
            m.put("gold", u.getGold());
            m.put("admin", u.isAdmin());
            m.put("banned", u.isBanned());
            m.put("online", presence.isOnline(u.getId()));
            m.put("vip", vipService.effectiveLevel(u));
            m.put("createdAt", u.getCreatedAt() == null ? null : u.getCreatedAt().toString());
            out.add(m);
        }
        out.sort(Comparator.comparingLong(m -> ((Number) m.get("id")).longValue()));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{id}/admin")
    public ResponseEntity<?> setAdmin(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @PathVariable long id, @RequestBody FlagBody body) {
        long me = actingId(auth);
        if (!body.value() && id == me) return err("不能取消自己的管理员权限（避免把后台锁死）");
        Optional<User> u = authService.setAdmin(id, body.value());
        u.ifPresent(x -> log.info("管理员操作 {}(id={}) 设置用户 id={} 管理员权限={}", me, me, id, body.value()));
        return u.<ResponseEntity<?>>map(x -> ResponseEntity.ok(Map.of("ok", true, "id", id, "admin", x.isAdmin())))
                .orElseGet(() -> err("用户不存在"));
    }

    @PostMapping("/{id}/ban")
    public ResponseEntity<?> setBan(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable long id, @RequestBody FlagBody body) {
        long me = actingId(auth);
        if (body.value() && id == me) return err("不能封禁自己");
        Optional<User> u = authService.setBanned(id, body.value());
        u.ifPresent(x -> log.info("管理员操作 {}(id={}) {}用户 id={}", me, me, body.value() ? "封禁" : "解封", id));
        return u.<ResponseEntity<?>>map(x -> ResponseEntity.ok(Map.of("ok", true, "id", id, "banned", x.isBanned())))
                .orElseGet(() -> err("用户不存在"));
    }

    @PostMapping("/{id}/password")
    public ResponseEntity<?> resetPw(@PathVariable long id, @RequestBody PwBody body) {
        if (users.findById(id).isEmpty()) return err("用户不存在");
        try {
            authService.resetPassword(id, body.password());
            // 审计：重置密码（严禁记录新密码明文）。
            log.info("管理员操作 重置用户 id={} 的密码", id);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    /** 删除账户：级联清理房间座位/好友/申请/私聊，并踢下线。不能删除自己。 */
    @PostMapping("/{id}/delete")
    @Transactional
    public ResponseEntity<?> deleteAccount(@RequestHeader(value = "Authorization", required = false) String auth,
                                           @PathVariable long id) {
        long me = actingId(auth);
        if (id == me) return err("不能删除自己的账号");
        if (users.findById(id).isEmpty()) return err("用户不存在");
        cascadeDelete(id);
        log.info("管理员操作 {}(id={}) 删除用户 id={}", me, me, id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    public record VipBody(int level, int days) {}

    /** 管理员授予/调整 VIP（level 0 取消；days<=0 表示不过期）。 */
    @PostMapping("/{id}/vip")
    public ResponseEntity<?> setVip(@PathVariable long id, @RequestBody VipBody body) {
        if (body.level() < 0 || body.level() > 3) return err("VIP 等级需为 0-3");
        Optional<User> u = vipService.grant(id, body.level(), body.days());
        return u.<ResponseEntity<?>>map(x -> ResponseEntity.ok(Map.of("ok", true, "id", id, "vip", vipService.effectiveLevel(x))))
                .orElseGet(() -> err("用户不存在"));
    }

    public record GoldBody(long delta) {}

    /** 管理员调整单个用户金币（delta 可为负，结果不为负）。 */
    @PostMapping("/{id}/gold")
    @Transactional
    public ResponseEntity<?> adjustGold(@PathVariable long id, @RequestBody GoldBody body) {
        Optional<User> opt = users.findById(id);
        if (opt.isEmpty()) return err("用户不存在");
        long bal = applyGold(opt.get(), body.delta(), "管理员调整");
        log.info("管理员操作 调整用户 id={} 金币 delta={} 余额={}", id, body.delta(), bal);
        return ResponseEntity.ok(Map.of("ok", true, "id", id, "gold", bal));
    }

    public record BatchBody(List<Long> ids, String op, Long value, Integer days) {}

    /**
     * 批量用户操作。op ∈ {ban, admin, vip, gold, delete}；
     * value：ban/admin 为 0|1，vip 为等级(0-3)，gold 为增减额（可负）；days 供 vip 使用。
     * 涉及“取消自身管理员/封禁自身/删除自身”的项会被跳过（避免把管理员锁死）。
     */
    @PostMapping("/batch")
    @Transactional
    public ResponseEntity<?> batch(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @RequestBody BatchBody body) {
        long me = actingId(auth);
        if (body.ids() == null || body.ids().isEmpty()) return err("未选择任何用户");
        if (body.ids().size() > 500) return err("单次批量最多 500 人");
        String op = body.op() == null ? "" : body.op();
        long value = body.value() == null ? 0 : body.value();
        int days = body.days() == null ? 30 : body.days();
        int done = 0, skipped = 0;
        for (Long id : body.ids()) {
            if (id == null) { skipped++; continue; }
            Optional<User> opt = users.findById(id);
            if (opt.isEmpty()) { skipped++; continue; }
            switch (op) {
                case "ban" -> {
                    if (id == me && value != 0) { skipped++; continue; }
                    authService.setBanned(id, value != 0); done++;
                    log.info("管理员操作 {}(id={}) 批量{}用户 id={}", me, me, value != 0 ? "封禁" : "解封", id);
                }
                case "admin" -> {
                    if (id == me && value == 0) { skipped++; continue; }
                    authService.setAdmin(id, value != 0); done++;
                    log.info("管理员操作 {}(id={}) 批量设置用户 id={} 管理员权限={}", me, me, id, value != 0);
                }
                case "vip" -> {
                    if (vipService.grant(id, (int) Math.max(0, Math.min(3, value)), days).isPresent()) {
                        done++;
                        log.info("管理员操作 {}(id={}) 批量授予用户 id={} VIP 等级={} 天数={}", me, me, id, value, days);
                    } else skipped++;
                }
                case "gold" -> {
                    long bal = applyGold(opt.get(), value, "管理员批量调整");
                    done++;
                    log.info("管理员操作 {}(id={}) 批量调整用户 id={} 金币 delta={} 余额={}", me, me, id, value, bal);
                }
                case "delete" -> {
                    if (id == me) { skipped++; continue; }
                    cascadeDelete(id); done++;
                    log.info("管理员操作 {}(id={}) 批量删除用户 id={}", me, me, id);
                }
                default -> { }
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "op", op, "done", done, "skipped", skipped));
    }

    private long applyGold(User u, long delta, String reason) {
        long before = u.getGold();
        long after = Math.max(0, before + delta);
        long applied = after - before;
        u.setGold(after);
        users.save(u);
        if (applied != 0) {
            GoldTransaction gt = new GoldTransaction();
            gt.setUserId(u.getId()); gt.setDelta(applied); gt.setBalanceAfter(after); gt.setReason(reason);
            goldRepo.save(gt);
        }
        return after;
    }

    /** 删除账户（批量用）：级联清理座位/好友/申请/私聊/战绩/流水/统计/背包/兑换/工单，并踢下线。 */
    private void cascadeDelete(long id) {
        authService.revokeSessions(id);
        roomPlayers.deleteByUserId(id);
        friendships.deleteByUserId(id);
        friendships.deleteByFriendId(id);
        friendRequests.deleteByFromId(id);
        friendRequests.deleteByToId(id);
        messages.deleteByFromId(id);
        messages.deleteByToId(id);
        // 追加：战绩、金币流水、角色统计、背包、兑换记录
        matchParticipants.deleteByUserId(id);
        goldRepo.deleteByUserId(id);
        roleStats.deleteByUserId(id);
        inventories.deleteByUserId(id);
        redeemUsages.deleteByUserId(id);
        // 工单：先按 reporterId 找到该用户所有工单，删除其下全部回复，再删工单本身；
        // 同时删除该用户作为作者发出的回复（如管理员回复他人工单）。
        List<UserTicket> tickets = userTickets.findByReporterIdOrderByIdDesc(id);
        List<Long> ticketIds = new ArrayList<>();
        for (UserTicket t : tickets) ticketIds.add(t.getId());
        if (!ticketIds.isEmpty()) ticketReplies.deleteByTicketIdIn(ticketIds);
        ticketReplies.deleteByAuthorId(id);
        userTickets.deleteByReporterId(id);
        // 结算页互动记录：清理该用户发出与收到的全部点赞/安慰/举报，避免孤儿数据
        gameKudos.deleteByFromUserId(id);
        gameKudos.deleteByToUserId(id);
        users.deleteById(id);
    }

    public record EditBody(String nickname, Integer level, Long exp, Long gold) {}

    /** 修改用户数据（昵称 / 等级 / 经验 / 金币），仅传入的字段会被更新。 */
    @PostMapping("/{id}/edit")
    public ResponseEntity<?> editUser(@PathVariable long id, @RequestBody EditBody body) {
        Optional<User> opt = users.findById(id);
        if (opt.isEmpty()) return err("用户不存在");
        User u = opt.get();
        if (body.nickname() != null && !body.nickname().isBlank()) u.setNickname(clamp(body.nickname(), 16));
        if (body.level() != null && body.level() >= 1) u.setLevel(Math.min(com.werewolf.game.SettlementService.MAX_LEVEL, body.level()));
        if (body.exp() != null && body.exp() >= 0) u.setExp(body.exp());
        if (body.gold() != null && body.gold() >= 0) u.setGold(body.gold());
        users.save(u);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true); m.put("id", u.getId()); m.put("nickname", u.getNickname());
        m.put("level", u.getLevel()); m.put("exp", u.getExp()); m.put("gold", u.getGold());
        return ResponseEntity.ok(m);
    }

    /** 以该用户身份登录：签发一个会话 token，供管理员进入其账号排查问题。 */
    @PostMapping("/{id}/impersonate")
    public ResponseEntity<?> impersonate(@PathVariable long id) {
        Optional<User> opt = users.findById(id);
        if (opt.isEmpty()) return err("用户不存在");
        User u = opt.get();
        if (u.isBanned()) return err("该用户已被封禁，无法进入");
        String token = authService.issueToken(id);
        return ResponseEntity.ok(Map.of("ok", true, "token", token, "nickname", u.getNickname(), "username", u.getUsername()));
    }

    private static String clamp(String s, int max) { if (s == null) return null; s = s.trim(); return s.length() > max ? s.substring(0, max) : s; }

    private long actingId(String auth) {
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        return authService.resolve(token).map(User::getId).orElse(-1L);
    }

    private ResponseEntity<?> err(String msg) { return ResponseEntity.badRequest().body(Map.of("error", msg)); }
}
