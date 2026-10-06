package com.werewolf.service;

import com.werewolf.model.GoldTransaction;
import com.werewolf.model.User;
import com.werewolf.repo.GoldTransactionRepository;
import com.werewolf.repo.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * VIP 会员：三档（白银/黄金/钻石），金币购买、可管理员授予。
 * 权益：商店折扣、专属称号、入场特效、签到加成。
 */
@Service
public class VipService {

    public static final class Tier {
        public final int level; public final String name; public final long price; public final int days;
        public final double discount; public final String title; public final int effect; public final int checkinBonusPct;
        Tier(int level, String name, long price, int days, double discount, String title, int effect, int checkinBonusPct) {
            this.level = level; this.name = name; this.price = price; this.days = days; this.discount = discount;
            this.title = title; this.effect = effect; this.checkinBonusPct = checkinBonusPct;
        }
        Map<String, Object> map() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", level); m.put("name", name); m.put("price", price); m.put("days", days);
            m.put("discount", discount); m.put("title", title); m.put("effect", effect); m.put("checkinBonusPct", checkinBonusPct);
            return m;
        }
    }

    public static final List<Tier> TIERS = List.of(
            new Tier(1, "白银会员", 800, 30, 0.90, "白银·荣耀", 1, 20),
            new Tier(2, "黄金会员", 2000, 30, 0.80, "黄金·尊贵", 2, 50),
            new Tier(3, "钻石会员", 5000, 30, 0.70, "钻石·王者", 3, 100)
    );

    private final UserRepository users;
    private final GoldTransactionRepository goldRepo;

    public VipService(UserRepository users, GoldTransactionRepository goldRepo) {
        this.users = users;
        this.goldRepo = goldRepo;
    }

    public static Tier tier(int level) {
        for (Tier t : TIERS) if (t.level == level) return t;
        return null;
    }

    /** 当前生效等级（过期视为 0）。 */
    public int effectiveLevel(User u) {
        if (u == null || u.getVipLevel() <= 0) return 0;
        if (u.getVipExpireAt() != null && u.getVipExpireAt().isBefore(LocalDateTime.now())) return 0;
        return u.getVipLevel();
    }

    public double discount(User u) {
        Tier t = tier(effectiveLevel(u));
        return t == null ? 1.0 : t.discount;
    }

    public int checkinBonusPct(User u) {
        Tier t = tier(effectiveLevel(u));
        return t == null ? 0 : t.checkinBonusPct;
    }

    /** 折扣后价格（向上取整到整数金币）。 */
    public long priceFor(User u, long basePrice) {
        double d = discount(u);
        return d >= 1.0 ? basePrice : (long) Math.ceil(basePrice * d);
    }

    public Map<String, Object> info(User u) {
        int lv = effectiveLevel(u);
        Tier t = tier(lv);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", lv);
        m.put("name", t == null ? "普通玩家" : t.name);
        m.put("active", lv > 0);
        m.put("expireAt", u.getVipExpireAt() == null ? null : u.getVipExpireAt().toString());
        m.put("discount", t == null ? 1.0 : t.discount);
        m.put("title", t == null ? null : t.title);
        m.put("effect", t == null ? 0 : t.effect);
        m.put("checkinBonusPct", t == null ? 0 : t.checkinBonusPct);
        m.put("gold", u.getGold());
        List<Map<String, Object>> tiers = new ArrayList<>();
        for (Tier x : TIERS) tiers.add(x.map());
        m.put("tiers", tiers);
        return m;
    }

    @Transactional
    public String purchase(User u, int level) {
        Tier t = tier(level);
        if (t == null) return "无效的会员等级";
        int cur = effectiveLevel(u);
        if (level < cur) return "你已是更高等级会员";
        // 原子扣减金币：数据库层条件更新（gold >= price），返回 0 表示余额不足
        int deducted = users.deductGold(u.getId(), t.price);
        if (deducted == 0) return "金币不足（需 " + t.price + "）";
        u.setGold(u.getGold() - t.price); // 同步内存余额，避免下方 save 时用旧值覆盖刚扣减的结果
        // 未过期则续期叠加，否则从现在起算
        LocalDateTime base = (u.getVipExpireAt() != null && u.getVipExpireAt().isAfter(LocalDateTime.now()))
                ? u.getVipExpireAt() : LocalDateTime.now();
        u.setVipLevel(Math.max(level, cur));
        u.setVipExpireAt(base.plusDays(t.days));
        users.save(u);
        long balanceAfter = users.findById(u.getId()).map(User::getGold).orElse(0L);
        GoldTransaction gt = new GoldTransaction();
        gt.setUserId(u.getId()); gt.setDelta(-t.price); gt.setBalanceAfter(balanceAfter);
        gt.setReason("开通 " + t.name + " " + t.days + "天");
        goldRepo.save(gt);
        return null;
    }

    /** 管理员授予/调整 VIP。days<=0 表示不过期(null)。 */
    @Transactional
    public Optional<User> grant(long userId, int level, int days) {
        Optional<User> u = users.findById(userId);
        u.ifPresent(x -> {
            x.setVipLevel(Math.max(0, level));
            x.setVipExpireAt(level <= 0 ? null : (days <= 0 ? null : LocalDateTime.now().plusDays(days)));
            users.save(x);
        });
        return u;
    }
}
