package com.werewolf.api;

import com.werewolf.model.GameKudos;
import com.werewolf.model.MatchParticipant;
import com.werewolf.model.User;
import com.werewolf.repo.GameKudosRepository;
import com.werewolf.repo.GameRecordRepository;
import com.werewolf.repo.MatchParticipantRepository;
import com.werewolf.repo.UserRepository;
import com.werewolf.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 结算页互动：对某玩家点赞 / 安慰 / 举报（按对局记录）。 */
@RestController
@RequestMapping("/api/game/kudos")
public class KudosController {

    private final AuthService authService;
    private final GameKudosRepository kudos;
    private final GameRecordRepository records;
    private final MatchParticipantRepository participants;
    private final UserRepository users;

    public KudosController(AuthService authService, GameKudosRepository kudos,
                           GameRecordRepository records, MatchParticipantRepository participants,
                           UserRepository users) {
        this.authService = authService;
        this.kudos = kudos;
        this.records = records;
        this.participants = participants;
        this.users = users;
    }

    public record KudoBody(Long gameId, Long toUserId, String type, String note) {}

    @PostMapping
    @Transactional
    public ResponseEntity<?> add(@RequestHeader(value = "Authorization", required = false) String auth,
                                 @RequestBody KudoBody body) {
        Optional<User> u = authService.resolve(AuthController.stripBearer(auth));
        if (u.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        if (body.gameId() == null || body.toUserId() == null) return bad("缺少对局或目标");
        String type = body.type() == null ? "" : body.type().trim().toUpperCase();
        if (!List.of("PRAISE", "COMFORT", "REPORT").contains(type)) return bad("互动类型非法");
        long me = u.get().getId();
        if (body.toUserId() == me) return bad("不能对自己操作");
        // 归属校验：必须是对局参与者，且目标也必须是该局参与者，避免对任意 gameId/toUserId 刷互动
        if (records.findById(body.gameId()).isEmpty()) return bad("对局不存在");
        if (users.findById(body.toUserId()).isEmpty()) return bad("目标用户不存在");
        Set<Long> seatUsers = new HashSet<>();
        for (MatchParticipant mp : participants.findByGameId(body.gameId())) seatUsers.add(mp.getUserId());
        if (!seatUsers.contains(me)) return bad("你不是本局参与者");
        if (!seatUsers.contains(body.toUserId())) return bad("目标不是本局参与者");
        // 同一局、同一目标、同一类型只记一次（举报也可重复反馈，但做轻量频率保护：同类型同目标最多 3 次）
        if (!"REPORT".equals(type)) {
            long dup = kudos.findByGameId(body.gameId()).stream()
                    .filter(k -> k.getFromUserId().equals(me) && k.getToUserId().equals(body.toUserId()) && k.getType().equals(type))
                    .count();
            if (dup > 0) return bad("已经操作过啦");
        } else {
            long rep = kudos.findByGameId(body.gameId()).stream()
                    .filter(k -> k.getFromUserId().equals(me) && k.getToUserId().equals(body.toUserId()) && k.getType().equals(type))
                    .count();
            if (rep >= 3) return bad("举报次数已达上限");
        }
        GameKudos k = new GameKudos();
        k.setGameId(body.gameId()); k.setFromUserId(me); k.setToUserId(body.toUserId());
        k.setType(type); k.setNote(body.note() == null ? null : (body.note().length() > 200 ? body.note().substring(0, 200) : body.note()));
        kudos.save(k);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 查询某局的互动统计（供结算页展示每人收到的赞/安慰/举报数）。 */
    @GetMapping("/{gameId}")
    public ResponseEntity<?> summary(@PathVariable long gameId) {
        Map<Long, Map<String, Long>> byUser = new LinkedHashMap<>();
        for (Object[] row : kudos.countByGame(gameId)) {
            long to = ((Number) row[0]).longValue();
            String t = String.valueOf(row[1]);
            long cnt = ((Number) row[2]).longValue();
            byUser.computeIfAbsent(to, k -> new LinkedHashMap<>()).put(t, cnt);
        }
        return ResponseEntity.ok(Map.of("gameId", gameId, "byUser", byUser));
    }

    private ResponseEntity<?> bad(String m) { return ResponseEntity.badRequest().body(Map.of("error", m)); }
}
