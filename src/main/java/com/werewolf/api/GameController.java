package com.werewolf.api;

import com.werewolf.game.GameAction;
import com.werewolf.game.LiveGameService;
import com.werewolf.game.SeatInfo;
import com.werewolf.model.Room;
import com.werewolf.model.User;
import com.werewolf.rules.BoardConfig;
import com.werewolf.rules.Role;
import com.werewolf.service.AuthService;
import com.werewolf.service.RoomService;
import com.werewolf.config.VoiceProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/game")
public class GameController {

    private final AuthService authService;
    private final RoomService roomService;
    private final LiveGameService gameService;
    private final com.werewolf.service.BoardService boardService;
    private final VoiceProperties vprops;

    public GameController(AuthService authService, RoomService roomService, LiveGameService gameService,
                          com.werewolf.service.BoardService boardService, VoiceProperties vprops) {
        this.authService = authService;
        this.roomService = roomService;
        this.gameService = gameService;
        this.boardService = boardService;
        this.vprops = vprops;
    }

    public record StartBody(Boolean allBots) {}
    public record ActionBody(Integer target, Integer saveTarget, Integer poisonTarget, String text, Boolean signup) {}

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @RequestBody(required = false) StartBody body) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        User host = u.get();
        Optional<Room> roomOpt = roomService.findRoomOfUser(host.getId());
        if (roomOpt.isEmpty()) return err("你不在任何房间");
        Room room = roomOpt.get();
        if (!room.getHostUserId().equals(host.getId())) return err("只有房主可以开局");
        if (gameService.get(room.getId()) != null) return err("本房间已有进行中的对局");

        RoomService.RoomView view = roomService.view(room);
        boolean allBots = body != null && Boolean.TRUE.equals(body.allBots());

        // 座位计划：真人 + 房间内补位 AI（房主排第一）
        List<SeatInfo> seats = new ArrayList<>();
        if (!allBots) {
            view.players().stream()
                    .sorted(Comparator.comparingInt(p -> p.userId() == host.getId() ? 0 : 1))
                    .forEach(p -> seats.add(new SeatInfo(p.seat(), p.userId(), p.nickname(), p.avatarId(),
                            p.ai(), p.avatarUrl(), p.nickColor(), p.frameColor(), p.title(), p.vip())));
        }
        int roomCount = view.players().size();
        // 优先用房主设置的板子；否则按人数自动选预设
        BoardConfig board = null;
        Map<String, Integer> roomBoard = boardService.parse(room.getBoardConfig());
        if (roomBoard != null && !allBots) {
            board = boardService.toBoard(roomBoard);
            if (roomCount > board.total()) {
                return err("板子人数（" + board.total() + "）少于房间内玩家数（" + roomCount + "），请换更大的板子");
            }
        }
        if (board == null) {
            int minTotal = allBots ? 9 : Math.max(6, roomCount);
            board = boardFor(minTotal);
        }
        if (board == null) return err("人数过多，暂不支持超过 24 人的板子");

        roomService.markPlaying(room.getId());
        boolean voiceMode = room.isVoiceMode() && vprops.isEnabled();
        boolean anonymous = room.isAnonymous() || voiceMode; // 语音模式强制匿名伪装以藏 AI
        boolean huntByBorder = !room.isHuntCity(); // 屠边（默认）或数量规则（屠城向，房主在对局模式切换）
        gameService.start(room.getId(), room.getRoomNo(), seats, board, allBots, huntByBorder, System.nanoTime(),
                voiceMode, anonymous, room.isItemMatch(), host.getId(), roomService.duosOf(room.getId()));
        return ok(Map.of("ok", true, "roomNo", room.getRoomNo(), "board", board.toString(),
                "voiceMode", voiceMode, "anonymous", anonymous));
    }

    /** 房主强制结束本局：终止所有进行中的 AI/LLM 调用与定时器，复位房间。 */
    @PostMapping("/end")
    public ResponseEntity<?> end(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        Optional<Room> roomOpt = roomService.findRoomOfUser(u.get().getId());
        if (roomOpt.isEmpty()) return err("你不在任何房间");
        String error = gameService.forceEnd(roomOpt.get().getId(), u.get().getId());
        if (error != null) return err(error);
        return ok(Map.of("ok", true));
    }

    @PostMapping("/action")
    public ResponseEntity<?> action(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody ActionBody body) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        Optional<Room> roomOpt = roomService.findRoomOfUser(u.get().getId());
        if (roomOpt.isEmpty()) return err("你不在任何房间");
        GameAction ga = new GameAction();
        ga.target = body.target() == null ? 0 : body.target();
        ga.saveTarget = body.saveTarget() == null ? 0 : body.saveTarget();
        ga.poisonTarget = body.poisonTarget() == null ? 0 : body.poisonTarget();
        ga.text = body.text();
        ga.signup = Boolean.TRUE.equals(body.signup());
        String error = gameService.applyHumanAction(roomOpt.get().getId(), u.get().getId(), ga);
        if (error != null) return err(error);
        return ok(Map.of("ok", true));
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestBody ActionBody body) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        Optional<Room> roomOpt = roomService.findRoomOfUser(u.get().getId());
        if (roomOpt.isEmpty()) return err("你不在任何房间");
        String error = gameService.chat(roomOpt.get().getId(), u.get().getId(), body.text());
        if (error != null) return err(error);
        return ok(Map.of("ok", true));
    }

    @GetMapping("/state")
    public ResponseEntity<?> state(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        Optional<Room> roomOpt = roomService.findRoomOfUser(u.get().getId());
        if (roomOpt.isEmpty()) return err("你不在任何房间");
        Map<String, Object> v = gameService.viewFor(roomOpt.get().getId(), u.get().getId());
        if (v == null) return err("本房间无进行中的对局");
        return ResponseEntity.ok(v);
    }

    /* ---------- 板子选择 ---------- */

    /** 按人数自动生成一副合法且平衡的板子（6~24 人）：狼约 1/4（≤6 且 < 好人），尽量铺满神职，其余平民。 */
    private BoardConfig boardFor(int total) {
        if (total < 6 || total > 24) return null;
        int wolves = Math.min(6, Math.max(2, total / 4));
        if (wolves * 2 >= total) wolves = Math.max(1, total / 2 - 1);   // 保证 狼数 < 好人数
        int remaining = total - wolves;                                  // 好人席位
        List<Role> specials = List.of(Role.SEER, Role.WITCH, Role.HUNTER, Role.GUARD,
                Role.IDIOT, Role.CROW, Role.SILENCER);
        BoardConfig b = new BoardConfig().put(Role.WEREWOLF, wolves);
        int nSpecial = Math.min(specials.size(), Math.max(0, remaining - 2)); // 至少留 2 名平民
        for (int i = 0; i < nSpecial; i++) b.put(specials.get(i), 1);
        int villagers = remaining - nSpecial;
        if (villagers > 0) b.put(Role.VILLAGER, villagers);
        return b;
    }

    /* ---------- helpers ---------- */

    private Optional<User> resolve(String auth) {
        return authService.resolve(AuthController.stripBearer(auth));
    }

    private ResponseEntity<?> ok(Object body) { return ResponseEntity.ok(body); }
    private ResponseEntity<?> err(String msg) { return ResponseEntity.badRequest().body(Map.of("error", msg)); }
}
