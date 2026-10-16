package com.werewolf.api;

import com.werewolf.game.LiveGameService;
import com.werewolf.model.Room;
import com.werewolf.model.User;
import com.werewolf.rules.BoardConfig;
import com.werewolf.service.AuthService;
import com.werewolf.service.BoardService;
import com.werewolf.service.RoomService;
import com.werewolf.ws.RoomBroadcaster;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 全 AI 队局（观战房）：整局全部由 AI 出战，真人玩家可进入房间观战。
 * 房主不入座，仅作为观战席；一局自然结束后会自动续开下一局（只要还有人在看）。
 */
@RestController
@RequestMapping("/api/ai-show")
public class AiShowController {

    private final AuthService authService;
    private final RoomService roomService;
    private final LiveGameService gameService;
    private final BoardService boardService;
    private final RoomBroadcaster broadcaster;

    public AiShowController(AuthService authService, RoomService roomService, LiveGameService gameService,
                            BoardService boardService, RoomBroadcaster broadcaster) {
        this.authService = authService;
        this.roomService = roomService;
        this.gameService = gameService;
        this.boardService = boardService;
        this.broadcaster = broadcaster;
    }

    public record CreateBody(String boardId) {}

    /** 观战房可选板子列表。 */
    @GetMapping("/boards")
    public ResponseEntity<?> boards(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (authService.resolve(AuthController.stripBearer(auth)).isEmpty()) return err("未登录或会话已过期");
        return ResponseEntity.ok(Map.of("boards", roomService.aiShowBoards()));
    }

    /** 正在进行的全 AI 观战房列表（登录即可查看，用于大厅展示与一键观战）。 */
    @GetMapping("/list")
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (authService.resolve(AuthController.stripBearer(auth)).isEmpty()) return err("未登录或会话已过期");
        return ResponseEntity.ok(Map.of("shows", roomService.listAiShows()));
    }

    /** 创建一间全 AI 观战房并立即开局。房主成为观战者。 */
    @PostMapping("/create")
    public ResponseEntity<?> create(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody(required = false) CreateBody body) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        User host = u.get();
        String boardId = body == null ? null : body.boardId();
        RoomService.ActionResult r = roomService.createAiShow(host, boardId);
        if (r.error() != null) return err(r.error());

        Room room = roomService.findRoomOfUser(host.getId()).orElse(null);
        if (room == null) return err("观战房创建失败");
        BoardConfig board = boardService.toBoard(roomService.resolveAiShowBoard(room.getBoardName()));
        String err = gameService.startAiShow(room, board);
        if (err != null) {
            // 开局失败则回收刚建的空房，避免留下无法使用的僵尸房
            roomService.adminDissolve(room.getId());
            return err(err);
        }
        broadcaster.broadcastState(room.getId(), roomService.view(room));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("roomNo", room.getRoomNo());
        out.put("boardName", room.getBoardName());
        out.put("room", roomService.view(room));
        return ResponseEntity.ok(out);
    }

    /** 房主手动续开一局（当前局已结束时可用）。 */
    @PostMapping("/restart")
    public ResponseEntity<?> restart(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        Room room = roomService.findRoomOfUser(u.get().getId()).orElse(null);
        if (room == null) return err("你不在任何房间");
        if (!room.isAiOnly()) return err("这不是全 AI 观战房");
        if (!room.getHostUserId().equals(u.get().getId())) return err("只有房主可以续开");
        if (gameService.get(room.getId()) != null) return err("当前对局尚未结束");
        BoardConfig board = boardService.toBoard(roomService.resolveAiShowBoard(room.getBoardName()));
        String err = gameService.startAiShow(room, board);
        if (err != null) return err(err);
        broadcaster.broadcastState(room.getId(), roomService.view(room));
        return ResponseEntity.ok(Map.of("ok", true, "roomNo", room.getRoomNo()));
    }

    /** 房主停止观战房（终止当前对局，房间保留，可再次续开）。 */
    @PostMapping("/stop")
    public ResponseEntity<?> stop(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        Room room = roomService.findRoomOfUser(u.get().getId()).orElse(null);
        if (room == null) return err("你不在任何房间");
        if (!room.getHostUserId().equals(u.get().getId())) return err("只有房主可以停止");
        String err = gameService.forceEndByAdmin(room.getId());
        if (err != null) return err(err);
        broadcaster.broadcastState(room.getId(), roomService.view(room));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private Optional<User> resolve(String auth) {
        return authService.resolve(AuthController.stripBearer(auth));
    }

    private ResponseEntity<?> err(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
