package com.werewolf.service;

import com.werewolf.model.Inventory;
import com.werewolf.model.ItemDefinition;
import com.werewolf.model.User;
import com.werewolf.repo.InventoryRepository;
import com.werewolf.repo.ItemDefinitionRepository;
import com.werewolf.repo.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class ProfileService {

    private final UserRepository userRepo;
    private final InventoryRepository invRepo;
    private final ItemDefinitionRepository itemRepo;
    private final com.werewolf.repo.GoldTransactionRepository goldTxRepo;
    private static final Path UPLOAD_DIR = Paths.get("uploads", "avatars");

    public ProfileService(UserRepository userRepo, InventoryRepository invRepo, ItemDefinitionRepository itemRepo,
                          com.werewolf.repo.GoldTransactionRepository goldTxRepo) {
        this.userRepo = userRepo;
        this.invRepo = invRepo;
        this.itemRepo = itemRepo;
        this.goldTxRepo = goldTxRepo;
    }

    /* ---------- 改名 ---------- */

    @Transactional
    public String changeNickname(User user, String nickname) {
        String nick = nickname == null ? "" : nickname.trim();
        if (nick.isEmpty() || nick.length() > 16) return "昵称需为 1-16 个字符";
        LocalDateTime last = user.getNicknameUpdatedAt();
        if (last != null && Duration.between(last, LocalDateTime.now()).toDays() < 3) {
            long left = 3 - Duration.between(last, LocalDateTime.now()).toDays();
            return "改名冷却中，还需约 " + left + " 天";
        }
        if (!nick.equals(user.getNickname()) && userRepo.existsByNickname(nick)) return "昵称已被占用";
        user.setNickname(nick);
        user.setNicknameUpdatedAt(LocalDateTime.now());
        userRepo.save(user);
        return null;
    }

    /* ---------- 头像上传（缩放压缩） ---------- */

    @Transactional
    public String uploadAvatar(User user, byte[] data, String contentType) {
        if (data == null || data.length == 0) return "文件为空";
        if (data.length > 2 * 1024 * 1024) return "头像不得超过 2MB";
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
            if (src == null) return "无法解析为图片";
            int size = 128;
            BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            // 居中裁剪为正方形
            int s = Math.min(src.getWidth(), src.getHeight());
            int sx = (src.getWidth() - s) / 2, sy = (src.getHeight() - s) / 2;
            g.drawImage(src, 0, 0, size, size, sx, sy, sx + s, sy + s, null);
            g.dispose();
            Files.createDirectories(UPLOAD_DIR);
            String fname = "u" + user.getId() + "_" + System.currentTimeMillis() + ".png";
            File f = UPLOAD_DIR.resolve(fname).toFile();
            ImageIO.write(out, "png", f);
            user.setAvatarUrl("/uploads/avatars/" + fname);
            userRepo.save(user);
            return null;
        } catch (IOException e) {
            return "头像处理失败：" + e.getMessage();
        }
    }

    /* ---------- QQ 号与 QQ 头像 ---------- */

    /** 补填 QQ 的一次性奖励金额。 */
    public static final long QQ_REWARD_GOLD = 1000;

    private static final javax.net.ssl.SSLContext TRUST_ALL_SSL;
    static {
        javax.net.ssl.SSLContext ctx = null;
        try {
            javax.net.ssl.TrustManager[] tm = new javax.net.ssl.TrustManager[]{ new javax.net.ssl.X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            }};
            ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, tm, new java.security.SecureRandom());
        } catch (Exception ignored) {}
        TRUST_ALL_SSL = ctx;
    }

    /**
     * 拉取 QQ 头像并保存为本账号头像。
     * 使用 qq.face1.vip 的公开头像服务（100/140/640 三档）。任一档失败自动降级；
     * 全部失败时返回 null（调用方决定是否报错，注册路径静默、手动设置路径提示）。
     */
    public String fetchQqAvatar(long userId, String qq) {
        String[] urls = {
                "https://q1.qlogo.cn/g?b=qq&nk=" + qq + "&s=640",
                "https://q2.qlogo.cn/g?b=qq&nk=" + qq + "&s=640",
                "https://q4.qlogo.cn/g?b=qq&nk=" + qq + "&s=140",
                "https://qq.face1.vip/api/qqlogo?qq=" + qq + "&size=640"
        };
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .sslContext(TRUST_ALL_SSL)
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
        for (String u : urls) {
            try {
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(u))
                        .timeout(java.time.Duration.ofSeconds(8))
                        .header("User-Agent", "Mozilla/5.0")
                        .GET().build();
                java.net.http.HttpResponse<byte[]> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() != 200) continue;
                byte[] body = resp.body();
                if (body == null || body.length < 1000) continue; // 空/占位图忽略
                java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(body));
                if (src == null) continue;
                java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(128, 128, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = out.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                int s2 = Math.min(src.getWidth(), src.getHeight());
                g.drawImage(src, 0, 0, 128, 128, (src.getWidth() - s2) / 2, (src.getHeight() - s2) / 2,
                        (src.getWidth() + s2) / 2, (src.getHeight() + s2) / 2, null);
                g.dispose();
                synchronized (UPLOAD_DIR) { Files.createDirectories(UPLOAD_DIR); }
                String fname = "u" + userId + "_qq_" + System.currentTimeMillis() + ".png";
                javax.imageio.ImageIO.write(out, "png", UPLOAD_DIR.resolve(fname).toFile());
                return "/uploads/avatars/" + fname;
            } catch (Exception ignored) { /* 尝试下一个源 */ }
        }
        return null;
    }

    /**
     * 设置 QQ 号：校验 5-11 位纯数字；保存头像；首次补填奖励 1000 金币（一次性）。
     * 返回 [错误, 奖励金币]：错误为 null 表示成功。
     */
    @Transactional
    public Object[] setQq(User passedUser, String qq) {
        String q = qq == null ? "" : qq.trim();
        if (q.isEmpty()) return new Object[]{"请输入 QQ 号", 0L};
        if (!q.matches("[1-9][0-9]{4,10}")) return new Object[]{"QQ 号需为 5-11 位数字（不以 0 开头）", 0L};
        // 关键：按 id 重新读取最新实体。Controller 传入的实体可能来自已结束的旧事务快照，
        // 其 @Version 已过期，直接 save 会触发乐观锁冲突（ObjectOptimisticLockingFailureException）。
        User user = userRepo.findById(passedUser.getId()).orElse(passedUser);
        user.setQq(q);
        boolean firstTime = !user.isQqRewarded();
        long rewarded = 0L;
        if (firstTime) {
            // 一次性奖励：先置标记再发币（同事务内），并发下由 @Version 乐观锁兜底
            user.setQqRewarded(true);
            user.setGold(user.getGold() + QQ_REWARD_GOLD);
            rewarded = QQ_REWARD_GOLD;
        }
        // 拉取 QQ 头像（失败不影响保存 QQ 号本身）
        try {
            String url = fetchQqAvatar(user.getId(), q);
            if (url != null) user.setAvatarUrl(url);
        } catch (Exception ignored) {}
        userRepo.save(user);
        if (rewarded > 0) {
            try {
                com.werewolf.model.GoldTransaction gt = new com.werewolf.model.GoldTransaction();
                gt.setUserId(user.getId()); gt.setDelta(rewarded); gt.setBalanceAfter(user.getGold());
                gt.setReason("补填 QQ 号奖励");
                goldTxRepo.save(gt);
            } catch (Exception ignored) {}
        }
        return new Object[]{null, rewarded};
    }

    /** 注册时填 QQ：保存号码并拉取 QQ 头像，不参与补填奖励。失败静默（注册优先成功）。 */
    @Transactional
    public void setQqAtRegister(User passedUser, String qq) {
        String q = qq == null ? "" : qq.trim();
        if (!q.matches("[1-9][0-9]{4,10}")) return;
        try {
            // 重新读取最新实体，避免旧快照的 @Version 触发乐观锁冲突
            User user = userRepo.findById(passedUser.getId()).orElse(passedUser);
            user.setQq(q);
            user.setQqRewarded(true);
            String url = fetchQqAvatar(user.getId(), q);
            if (url != null) user.setAvatarUrl(url);
            userRepo.save(user);
        } catch (Exception ignored) {}
    }

    /* ---------- 穿戴装饰 ---------- */

    @Transactional
    public String equip(User user, long itemDefId) {
        Optional<ItemDefinition> itemOpt = itemRepo.findById(itemDefId);
        if (itemOpt.isEmpty()) return "商品不存在";
        ItemDefinition item = itemOpt.get();
        if ("FUNCTION".equals(item.getType())) return "功能道具在对局中自动使用";
        if (!invRepo.existsByUserIdAndItemDefId(user.getId(), itemDefId)) return "你尚未拥有该物品";
        switch (item.getType()) {
            case "AVATAR" -> user.setAvatarId(Integer.parseInt(item.getAsset()));
            case "FRAME" -> user.setFrameId((int) itemDefId);
            case "TITLE" -> user.setTitleId((int) itemDefId);
            case "NICKCOLOR" -> user.setNickColor(item.getAsset());
            default -> { return "未知物品类型"; }
        }
        userRepo.save(user);
        return null;
    }

    @Transactional
    public void unequip(User user, String type) {
        switch (type) {
            case "FRAME" -> user.setFrameId(0);
            case "TITLE" -> user.setTitleId(0);
            case "NICKCOLOR" -> user.setNickColor(null);
            case "AVATAR" -> user.setAvatarId(1);
            default -> { return; }
        }
        userRepo.save(user);
    }

    /* ---------- 携带功能道具（带入下一局） ---------- */

    @Transactional
    public String carryItem(User user, Long itemDefId) {
        if (itemDefId == null || itemDefId == 0) { user.setCarryItemId(null); userRepo.save(user); return null; }
        ItemDefinition item = itemRepo.findById(itemDefId).orElse(null);
        if (item == null || !"FUNCTION".equals(item.getType())) return "不是可用的功能道具";
        Inventory inv = invRepo.findByUserIdAndItemDefId(user.getId(), itemDefId).orElse(null);
        if (inv == null || inv.isUsed()) return "你没有该道具或已用完";
        user.setCarryItemId(itemDefId);
        userRepo.save(user);
        return null;
    }
}
