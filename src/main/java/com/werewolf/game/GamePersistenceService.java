package com.werewolf.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对局快照的落盘与恢复（服务器重启后仍可继续未结束的对局）。
 *
 * <p>每个进行中的房间写一个快照文件 {@code data/games/<roomId>.snap}。
 * 快照只在「状态发生变化」时按房间节流写入（默认 2.5s），并在服务器优雅关闭时全量写一次，
 * 因此即使中途重启，也能从最近一次快照继续推进对局。</p>
 *
 * <p>本类只负责文件 I/O 与快照 DTO 的组装，不持有 LiveGameService，
 * 以避免与 LiveGameService（在 pushState 中调用本类保存）形成循环依赖。
 * 恢复对局的动作由 LiveGameService 在启动事件中调用 {@link #loadAll()} 完成。</p>
 */
@Service
public class GamePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(GamePersistenceService.class);

    /** 快照目录（相对工作目录，与 H2 数据库 data/ 同级）。 */
    private static final Path DIR = Paths.get("data", "games");

    /** 同一房间两次落盘的最小间隔（毫秒）：避免每次状态推送都写盘。 */
    private static final long THROTTLE_MS = 2500L;

    private final Map<Long, Long> lastSaveMs = new ConcurrentHashMap<>();
    private final ExecutorService writer =
            Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ww-snap"); t.setDaemon(true); return t; });

    /* ================= 保存 ================= */

    /** 异步保存（带节流）：状态推送时调用，不阻塞对局线程。 */
    public void saveAsync(LiveGame lg) {
        if (lg == null) return;
        long now = System.currentTimeMillis();
        Long last = lastSaveMs.get(lg.roomId);
        if (last != null && now - last < THROTTLE_MS) return;
        lastSaveMs.put(lg.roomId, now);
        try {
            writer.submit(() -> saveNow(lg));
        } catch (Exception e) {
            log.debug("提交快照任务失败 room={}: {}", lg.roomId, e.getMessage());
        }
    }

    /** 立即保存（同步）。序列化在 lg 锁内完成以保证一致性，文件写入在锁外完成。 */
    public void saveNow(LiveGame lg) {
        if (lg == null) return;
        byte[] data;
        try {
            synchronized (lg) {
                if (lg.finished || lg.aborted) return;   // 已结束/已退役的对局不再保存
                data = serialize(snapshotOf(lg));
            }
        } catch (Exception e) {
            log.warn("对局快照序列化失败 room={}: {}", lg.roomId, e.getMessage());
            return;
        }
        writeAtomic(lg.roomId, data);
    }

    /** 删除某房间的快照（对局结束/房间解散时调用）。 */
    public void delete(long roomId) {
        lastSaveMs.remove(roomId);
        try {
            Files.deleteIfExists(fileOf(roomId));
        } catch (Exception e) {
            log.debug("删除对局快照失败 room={}: {}", roomId, e.getMessage());
        }
    }

    /** 是否存在某房间的快照（供启动自愈判断房间是否可恢复）。 */
    public boolean hasSnapshot(long roomId) {
        try {
            return Files.exists(fileOf(roomId));
        } catch (Exception e) {
            return false;
        }
    }

    /* ================= 恢复 ================= */

    /** 读取全部快照（忽略损坏/版本不符的文件）。 */
    public List<GameSnapshot> loadAll() {
        List<GameSnapshot> out = new ArrayList<>();
        try {
            if (!Files.isDirectory(DIR)) return out;
            try (var stream = Files.list(DIR)) {
                for (Path p : stream.filter(x -> x.getFileName().toString().endsWith(".snap")).toList()) {
                    try {
                        byte[] data = Files.readAllBytes(p);
                        Object o = deserialize(data);
                        if (o instanceof GameSnapshot s && s.version == 1 && s.engine != null) {
                            out.add(s);
                        } else {
                            log.warn("忽略不兼容的对局快照: {}", p.getFileName());
                            Files.deleteIfExists(p);
                        }
                    } catch (Exception e) {
                        log.warn("读取对局快照失败 {}: {}", p.getFileName(), e.getMessage());
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            log.warn("扫描对局快照目录失败: {}", e.getMessage());
        }
        return out;
    }

    /* ================= 内部 ================= */

    private GameSnapshot snapshotOf(LiveGame lg) {
        GameSnapshot s = new GameSnapshot();
        s.roomId = lg.roomId;
        s.roomNo = lg.roomNo;
        s.engine = lg.engine;
        s.seats = lg.seats;
        s.userToSeat = lg.userToSeat;
        s.seed = lg.seed;
        s.huntByBorder = lg.huntByBorder;
        s.voiceMode = lg.voiceMode;
        s.anonymous = lg.anonymous;
        s.itemMatch = lg.itemMatch;
        s.aiOnly = lg.aiOnly;
        s.hostUserId = lg.hostUserId;
        s.gameId = lg.gameId;
        s.savedAt = System.currentTimeMillis();
        return s;
    }

    private Path fileOf(long roomId) {
        return DIR.resolve(roomId + ".snap");
    }

    private void writeAtomic(long roomId, byte[] data) {
        try {
            Files.createDirectories(DIR);
            Path tmp = DIR.resolve(roomId + ".snap.tmp");
            Files.write(tmp, data);
            try {
                Files.move(tmp, fileOf(roomId), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFail) {
                Files.move(tmp, fileOf(roomId), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("写入对局快照失败 room={}: {}", roomId, e.getMessage());
        }
    }

    private static byte[] serialize(GameSnapshot s) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(s);
        }
        return bos.toByteArray();
    }

    private static Object deserialize(byte[] data) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return ois.readObject();
        }
    }
}
