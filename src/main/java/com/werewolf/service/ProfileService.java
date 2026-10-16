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
    private static final Path UPLOAD_DIR = Paths.get("uploads", "avatars");

    public ProfileService(UserRepository userRepo, InventoryRepository invRepo, ItemDefinitionRepository itemRepo) {
        this.userRepo = userRepo;
        this.invRepo = invRepo;
        this.itemRepo = itemRepo;
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
            case "SKIN3D" -> user.setSkin3d(item.getAsset());
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
            case "SKIN3D" -> user.setSkin3d(null);
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
