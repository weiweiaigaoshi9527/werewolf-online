package com.werewolf.game;

import com.werewolf.model.Room;
import com.werewolf.repo.RoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 启动自愈：把「没有活跃对局却仍标记为进行中」的房间复位为等待中。
 * 服务重启/崩溃后，内存里的对局全部丢失，但数据库里的房间仍是 PLAYING，
 * 会让玩家看到一个永远无法推进的“游戏中”房间（且这类僵尸房无法被正常清理）。
 *
 * <p>注意：若某房间存在未结束对局快照（由 {@link GamePersistenceService} 落盘），
 * 说明该对局可被恢复，此时不能复位——恢复逻辑在
 * {@link LiveGameService#restoreAll()} 中执行，且优先级高于本监听器。</p>
 */
@Component
public class StaleGameResetter {

    private static final Logger log = LoggerFactory.getLogger(StaleGameResetter.class);

    private final RoomRepository roomRepo;
    private final LiveGameService gameService;
    private final GamePersistenceService persistence;

    public StaleGameResetter(RoomRepository roomRepo, LiveGameService gameService,
                             GamePersistenceService persistence) {
        this.roomRepo = roomRepo;
        this.gameService = gameService;
        this.persistence = persistence;
    }

    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
    public void resetStaleRooms() {
        int n = 0;
        try {
            for (Room r : roomRepo.findAll()) {
                if (!"PLAYING".equals(r.getStatus())) continue;
                if (gameService.get(r.getId()) != null) continue;   // 有活跃对局则保留
                if (persistence.hasSnapshot(r.getId())) continue;   // 可恢复的对局不复位（等 restoreAll 恢复）
                r.setStatus("WAITING");
                r.setUpdatedAt(LocalDateTime.now());
                roomRepo.save(r);
                n++;
            }
        } catch (Exception e) {
            log.warn("启动复位残留房间失败: {}", e.getMessage());
        }
        if (n > 0) log.info("启动自愈：{} 个残留的「进行中」房间已复位为等待中", n);
    }
}
