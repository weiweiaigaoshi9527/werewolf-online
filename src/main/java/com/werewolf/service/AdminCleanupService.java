package com.werewolf.service;

import com.werewolf.game.LiveGameService;
import com.werewolf.model.Room;
import com.werewolf.model.RoomPlayer;
import com.werewolf.model.User;
import com.werewolf.model.UserTicket;
import com.werewolf.repo.FriendRequestRepository;
import com.werewolf.repo.FriendshipRepository;
import com.werewolf.repo.GameKudosRepository;
import com.werewolf.repo.GoldTransactionRepository;
import com.werewolf.repo.InventoryRepository;
import com.werewolf.repo.MatchParticipantRepository;
import com.werewolf.repo.PrivateMessageRepository;
import com.werewolf.repo.RedeemUsageRepository;
import com.werewolf.repo.RoleStatRepository;
import com.werewolf.repo.RoomPlayerRepository;
import com.werewolf.repo.RoomRepository;
import com.werewolf.repo.TicketReplyRepository;
import com.werewolf.repo.UserRepository;
import com.werewolf.repo.UserTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台「一键清理」：清理无用房间与无用账号。
 *
 * 无用房间：非进行中、无活跃对局、房内没有任何在线真人、且创建已超过阈值时长（等待中的“僵尸房”）。
 * 无用账号：非管理员、未被封禁、当前不在线、等级不超过阈值、对局数不超过阈值、且创建已超过阈值时长。
 *
 * 一律先 scan（预览）再 run（执行），避免误删。
 */
@Service
public class AdminCleanupService {

    private static final Logger log = LoggerFactory.getLogger(AdminCleanupService.class);

    private final UserRepository users;
    private final PresenceService presence;
    private final RoomRepository rooms;
    private final RoomPlayerRepository roomPlayers;
    private final RoomMembership membership;
    private final LiveGameService gameService;
    private final RoomService roomService;
    private final AuthService authService;

    // 账号级联删除所需仓库
    private final FriendshipRepository friendships;
    private final FriendRequestRepository friendRequests;
    private final PrivateMessageRepository messages;
    private final MatchParticipantRepository matchParticipants;
    private final GoldTransactionRepository goldRepo;
    private final RoleStatRepository roleStats;
    private final InventoryRepository inventories;
    private final RedeemUsageRepository redeemUsages;
    private final UserTicketRepository userTickets;
    private final TicketReplyRepository ticketReplies;
    private final GameKudosRepository gameKudos;

    public AdminCleanupService(UserRepository users, PresenceService presence, RoomRepository rooms,
                               RoomPlayerRepository roomPlayers, RoomMembership membership,
                               LiveGameService gameService, RoomService roomService, AuthService authService,
                               FriendshipRepository friendships, FriendRequestRepository friendRequests,
                               PrivateMessageRepository messages, MatchParticipantRepository matchParticipants,
                               GoldTransactionRepository goldRepo, RoleStatRepository roleStats,
                               InventoryRepository inventories, RedeemUsageRepository redeemUsages,
                               UserTicketRepository userTickets, TicketReplyRepository ticketReplies,
                               GameKudosRepository gameKudos) {
        this.users = users;
        this.presence = presence;
        this.rooms = rooms;
        this.roomPlayers = roomPlayers;
        this.membership = membership;
        this.gameService = gameService;
        this.roomService = roomService;
        this.authService = authService;
        this.friendships = friendships;
        this.friendRequests = friendRequests;
        this.messages = messages;
        this.matchParticipants = matchParticipants;
        this.goldRepo = goldRepo;
        this.roleStats = roleStats;
        this.inventories = inventories;
        this.redeemUsages = redeemUsages;
        this.userTickets = userTickets;
        this.ticketReplies = ticketReplies;
        this.gameKudos = gameKudos;
    }

    /** 清理参数（全部有安全默认值）。 */
    public record Options(Integer roomIdleHours, Integer accountMinAgeHours,
                          Integer accountMaxLevel, Integer accountMaxGames) {}

    private static int clampInt(Integer v, int def, int min, int max) {
        int x = v == null ? def : v;
        return Math.max(min, Math.min(max, x));
    }

    /** 预览：列出将被清理的房间与账号（不执行删除）。 */
    public Map<String, Object> scan(Options opt, long actingId) {
        int roomIdleHours = clampInt(opt == null ? null : opt.roomIdleHours(), 1, 0, 24 * 30);
        int accountMinAgeHours = clampInt(opt == null ? null : opt.accountMinAgeHours(), 72, 0, 24 * 365);
        int accountMaxLevel = clampInt(opt == null ? null : opt.accountMaxLevel(), 1, 0, 999);
        int accountMaxGames = clampInt(opt == null ? null : opt.accountMaxGames(), 0, 0, 100000);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rooms", describeRooms(uselessRooms(roomIdleHours), false));
        out.put("accounts", describeAccounts(uselessAccounts(accountMinAgeHours, accountMaxLevel, accountMaxGames, actingId), false));
        out.put("criteria", criteria(roomIdleHours, accountMinAgeHours, accountMaxLevel, accountMaxGames));
        return out;
    }

    /** 执行清理。doRooms/doAccounts 决定清理哪一类。 */
    @Transactional
    public Map<String, Object> run(boolean doRooms, boolean doAccounts, Options opt, long actingId) {
        int roomIdleHours = clampInt(opt == null ? null : opt.roomIdleHours(), 1, 0, 24 * 30);
        int accountMinAgeHours = clampInt(opt == null ? null : opt.accountMinAgeHours(), 72, 0, 24 * 365);
        int accountMaxLevel = clampInt(opt == null ? null : opt.accountMaxLevel(), 1, 0, 999);
        int accountMaxGames = clampInt(opt == null ? null : opt.accountMaxGames(), 0, 0, 100000);

        Map<String, Object> out = new LinkedHashMap<>();
        List<Room> deadRooms = doRooms ? uselessRooms(roomIdleHours) : List.of();
        List<User> deadAccounts = doAccounts ? uselessAccounts(accountMinAgeHours, accountMaxLevel, accountMaxGames, actingId) : List.of();

        int roomsDeleted = 0;
        for (Room r : deadRooms) {
            try {
                roomService.adminDissolve(r.getId());
                roomsDeleted++;
            } catch (Exception e) {
                log.warn("清理房间失败 room={}: {}", r.getRoomNo(), e.getMessage());
            }
        }
        int accountsDeleted = 0;
        for (User u : deadAccounts) {
            try {
                cascadeDeleteUser(u.getId());
                accountsDeleted++;
            } catch (Exception e) {
                log.warn("清理账号失败 id={}: {}", u.getId(), e.getMessage());
            }
        }
        out.put("rooms", describeRooms(deadRooms, true));
        out.put("accounts", describeAccounts(deadAccounts, true));
        ((Map<String, Object>) out.get("rooms")).put("deleted", roomsDeleted);
        ((Map<String, Object>) out.get("accounts")).put("deleted", accountsDeleted);
        out.put("criteria", criteria(roomIdleHours, accountMinAgeHours, accountMaxLevel, accountMaxGames));
        log.info("管理员 {}(id={}) 一键清理：删除房间 {} 间、账号 {} 个", actingId, actingId, roomsDeleted, accountsDeleted);
        return out;
    }

    /* ---------- 判定 ---------- */

    private List<Room> uselessRooms(int idleHours) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(idleHours);
        List<Room> out = new ArrayList<>();
        for (Room r : rooms.findAll()) {
            // 关键判据是「没有活跃对局」：服务重启后残留的 PLAYING 僵尸房同样属于无用房间，
            // 不能只按状态过滤，否则这类房间永远清不掉。
            if (gameService.get(r.getId()) != null) continue;           // 有活跃对局（含观战房续开中）不动
            if (r.getCreatedAt() != null && r.getCreatedAt().isAfter(cutoff)) continue; // 刚创建的不动
            boolean hasOnlineHuman = roomPlayers.findByRoomIdOrderBySeatNoAsc(r.getId()).stream()
                    .anyMatch(p -> !p.isAi() && presence.isOnline(p.getUserId()));
            if (hasOnlineHuman) continue;                               // 有真人在线不动
            out.add(r);
        }
        return out;
    }

    private List<User> uselessAccounts(int minAgeHours, int maxLevel, int maxGames, long actingId) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(minAgeHours);
        List<User> out = new ArrayList<>();
        for (User u : users.findAll()) {
            if (u.getId() != null && u.getId() == actingId) continue;   // 不能删自己
            if (u.isAdmin()) continue;                                  // 保留管理员
            if (u.isBanned()) continue;                                 // 保留封禁账号（可能申诉）
            if (presence.isOnline(u.getId())) continue;                 // 在线不动
            if (u.getLevel() > maxLevel) continue;
            if (u.getCreatedAt() != null && u.getCreatedAt().isAfter(cutoff)) continue;
            if (matchParticipants.countByUserId(u.getId()) > maxGames) continue;
            out.add(u);
        }
        return out;
    }

    private Map<String, Object> describeRooms(List<Room> list, boolean executed) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Room r : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("roomNo", r.getRoomNo());
            m.put("status", r.getStatus());
            m.put("hostName", users.findById(r.getHostUserId()).map(User::getNickname).orElse("#" + r.getHostUserId()));
            m.put("playerCount", roomPlayers.countByRoomId(r.getId()));
            m.put("aiOnly", r.isAiOnly());
            m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.size());
        out.put("items", items);
        if (executed) out.put("executed", true);
        return out;
    }

    private Map<String, Object> describeAccounts(List<User> list, boolean executed) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (User u : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("nickname", u.getNickname());
            m.put("level", u.getLevel());
            m.put("gold", u.getGold());
            m.put("games", matchParticipants.countByUserId(u.getId()));
            m.put("createdAt", u.getCreatedAt() == null ? null : u.getCreatedAt().toString());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.size());
        out.put("items", items);
        if (executed) out.put("executed", true);
        return out;
    }

    private Map<String, Object> criteria(int roomIdleHours, int accountMinAgeHours, int accountMaxLevel, int accountMaxGames) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("roomIdleHours", roomIdleHours);
        c.put("accountMinAgeHours", accountMinAgeHours);
        c.put("accountMaxLevel", accountMaxLevel);
        c.put("accountMaxGames", accountMaxGames);
        return c;
    }

    /* ---------- 账号级联删除（后台用户管理与一键清理共用） ---------- */

    /** 删除账户并级联清理其全部关联数据，同时踢下线。 */
    @Transactional
    public void cascadeDeleteUser(long id) {
        authService.revokeSessions(id);
        membership.removeEverywhere(id);
        roomPlayers.deleteByUserId(id);
        friendships.deleteByUserId(id);
        friendships.deleteByFriendId(id);
        friendRequests.deleteByFromId(id);
        friendRequests.deleteByToId(id);
        messages.deleteByFromId(id);
        messages.deleteByToId(id);
        matchParticipants.deleteByUserId(id);
        goldRepo.deleteByUserId(id);
        roleStats.deleteByUserId(id);
        inventories.deleteByUserId(id);
        redeemUsages.deleteByUserId(id);
        List<UserTicket> tickets = userTickets.findByReporterIdOrderByIdDesc(id);
        List<Long> ticketIds = new ArrayList<>();
        for (UserTicket t : tickets) ticketIds.add(t.getId());
        if (!ticketIds.isEmpty()) ticketReplies.deleteByTicketIdIn(ticketIds);
        ticketReplies.deleteByAuthorId(id);
        userTickets.deleteByReporterId(id);
        gameKudos.deleteByFromUserId(id);
        gameKudos.deleteByToUserId(id);
        users.deleteById(id);
    }
}
