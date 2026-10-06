package com.werewolf.service;

import com.werewolf.model.GoldTransaction;
import com.werewolf.model.Inventory;
import com.werewolf.model.RedeemCode;
import com.werewolf.model.RedeemUsage;
import com.werewolf.model.User;
import com.werewolf.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

/** 兑换码：用户兑换发奖 + 后台生成/停用。 */
@Service
public class RedeemService {

    /** 全局复用的安全随机源（线程安全），用于生成不可预测的兑换码。 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RedeemCodeRepository codes;
    private final RedeemUsageRepository usage;
    private final UserRepository users;
    private final GoldTransactionRepository goldRepo;
    private final InventoryRepository invRepo;

    public RedeemService(RedeemCodeRepository codes, RedeemUsageRepository usage, UserRepository users,
                         GoldTransactionRepository goldRepo, InventoryRepository invRepo) {
        this.codes = codes;
        this.usage = usage;
        this.users = users;
        this.goldRepo = goldRepo;
        this.invRepo = invRepo;
    }

    public static String randomCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder("WW");
        // 使用 SecureRandom 替代 java.util.Random，避免兑换码可被预测
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(SECURE_RANDOM.nextInt(chars.length())));
        return sb.toString();
    }

    @Transactional
    public String redeem(long userId, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) return "请输入兑换码";
        String code = rawCode.trim().toUpperCase();
        Optional<RedeemCode> found = codes.findByCode(code);
        if (found.isEmpty()) return "兑换码不存在";
        RedeemCode c = found.get();
        if (!c.isActive()) return "兑换码已停用";
        if (c.getExpireAt() != null && c.getExpireAt().isBefore(LocalDateTime.now())) return "兑换码已过期";
        if (c.getUsedCount() >= c.getMaxUses()) return "兑换码已被领完";
        // 该用户是否已兑换的判断放在占用之前；并发场景由 (code_id,user_id) 唯一约束兜底
        if (usage.existsByCodeIdAndUserId(c.getId(), userId)) return "你已兑换过该码";
        Optional<User> uf = users.findById(userId);
        if (uf.isEmpty()) return "用户不存在";
        // 先原子占用一次名额，成功后再发奖；返回 0 表示已被领完（并发下不会超发）
        int occupied = codes.consumeOne(c.getId(), c.getMaxUses());
        if (occupied == 0) return "兑换码已被领完";
        // 记录兑换：新增 RedeemUsage，唯一约束兜底同一用户重复兑换的并发场景
        RedeemUsage ru = new RedeemUsage();
        ru.setCodeId(c.getId()); ru.setUserId(userId);
        usage.save(ru);
        if (c.getGold() > 0) {
            // 原子加币
            users.addGold(userId, c.getGold());
            long balanceAfter = users.findById(userId).map(User::getGold).orElse(0L);
            GoldTransaction gt = new GoldTransaction();
            gt.setUserId(userId); gt.setDelta(c.getGold()); gt.setBalanceAfter(balanceAfter);
            gt.setReason("兑换码 " + code); goldRepo.save(gt);
        }
        if (c.getExp() > 0) {
            User u = users.findById(userId).orElseThrow();
            u.setExp(u.getExp() + c.getExp());
            users.save(u);
        }
        if (c.getItemDefId() > 0) {
            Inventory inv = new Inventory();
            inv.setUserId(userId); inv.setItemDefId((long) c.getItemDefId()); inv.setUsed(false);
            invRepo.save(inv);
        }
        return null; // 成功
    }

    @Transactional
    public RedeemCode create(String code, long gold, long exp, int itemDefId, int maxUses,
                             LocalDateTime expireAt, String note) {
        String c = (code == null || code.isBlank()) ? randomCode() : code.trim().toUpperCase();
        if (codes.findByCode(c).isPresent()) throw new IllegalArgumentException("兑换码已存在");
        RedeemCode r = new RedeemCode();
        r.setCode(c); r.setGold(gold); r.setExp(exp); r.setItemDefId(itemDefId);
        r.setMaxUses(Math.max(1, maxUses)); r.setExpireAt(expireAt); r.setNote(note);
        return codes.save(r);
    }

    @Transactional
    public void setActive(long id, boolean active) {
        codes.findById(id).ifPresent(r -> { r.setActive(active); codes.save(r); });
    }

    @Transactional
    public void delete(long id) { codes.deleteById(id); }

    public java.util.List<RedeemCode> list() { return codes.findAll(); }
}
