package com.werewolf.api;

import com.werewolf.game.LiveGameService;
import com.werewolf.service.RoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 后台·房间管理（仅管理员，/api/admin/** 已被拦截器保护）。 */
@RestController
@RequestMapping("/api/admin/rooms")
public class AdminRoomController {

    private final RoomService roomService;
    private final LiveGameService gameService;

    public AdminRoomController(RoomService roomService, LiveGameService gameService) {
        this.roomService = roomService;
        this.gameService = gameService;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(roomService.adminListRooms());
    }

    /** 强制结束该房间进行中的对局（复位为等待中）。 */
    @PostMapping("/{id}/end")
    public ResponseEntity<?> end(@PathVariable long id) {
        String err = gameService.forceEndByAdmin(id);
        return err == null ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.badRequest().body(Map.of("error", err));
    }

    /** 解散房间：先结束对局，再清理成员与房间。 */
    @PostMapping("/{id}/dissolve")
    public ResponseEntity<?> dissolve(@PathVariable long id) {
        gameService.forceEndByAdmin(id); // 若在进行中则结束（无对局时忽略返回）
        String err = roomService.adminDissolve(id);
        return err == null ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.badRequest().body(Map.of("error", err));
    }
}
