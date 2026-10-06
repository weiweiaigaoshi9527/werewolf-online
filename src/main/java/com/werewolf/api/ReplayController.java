package com.werewolf.api;

import com.werewolf.model.GameEventLog;
import com.werewolf.model.GameRecord;
import com.werewolf.model.User;
import com.werewolf.repo.GameEventLogRepository;
import com.werewolf.repo.GameRecordRepository;
import com.werewolf.repo.MatchParticipantRepository;
import com.werewolf.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 对局回放：读取落库的事件流（上帝视角，含夜晚动作）与结果。 */
@RestController
@RequestMapping("/api/game")
public class ReplayController {

    private final AuthService authService;
    private final GameRecordRepository recordRepo;
    private final GameEventLogRepository eventRepo;
    private final MatchParticipantRepository participantRepo;

    public ReplayController(AuthService authService, GameRecordRepository recordRepo,
                            GameEventLogRepository eventRepo, MatchParticipantRepository participantRepo) {
        this.authService = authService;
        this.recordRepo = recordRepo;
        this.eventRepo = eventRepo;
        this.participantRepo = participantRepo;
    }

    @GetMapping("/replay/{gameId}")
    public ResponseEntity<?> replay(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable long gameId) {
        Optional<User> u = authService.resolve(AuthController.stripBearer(auth));
        if (u.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "未登录或会话已过期"));
        Optional<GameRecord> rec = recordRepo.findById(gameId);
        if (rec.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "对局不存在"));

        // 越权校验（IDOR）：仅该局参与者可查看回放；管理员可放行
        boolean participant = participantRepo.findByGameId(gameId).stream()
                .anyMatch(mp -> u.get().getId().equals(mp.getUserId()));
        if (!participant && !u.get().isAdmin()) {
            return ResponseEntity.status(403).body(Map.of("error", "无权查看该对局回放"));
        }

        GameRecord r = rec.get();
        List<Map<String, Object>> events = new ArrayList<>();
        for (GameEventLog e : eventRepo.findByGameIdOrderBySeqAsc(gameId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", e.getSeq());
            m.put("day", e.getDay());
            m.put("phase", e.getPhase());
            m.put("type", e.getType());
            m.put("actor", e.getActorSeat());
            m.put("target", e.getTargetSeat());
            m.put("detail", e.getDetail());
            events.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gameId", r.getId());
        out.put("roomNo", r.getRoomNo());
        out.put("winner", r.getWinner());
        out.put("seatRoles", r.getSeatRoles());
        out.put("startedAt", r.getStartedAt() == null ? null : r.getStartedAt().toString());
        out.put("endedAt", r.getEndedAt() == null ? null : r.getEndedAt().toString());
        out.put("events", events);
        return ResponseEntity.ok(out);
    }
}
