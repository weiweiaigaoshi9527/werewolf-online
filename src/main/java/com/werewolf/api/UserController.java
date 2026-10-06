package com.werewolf.api;

import com.werewolf.game.SettlementService;
import com.werewolf.model.GoldTransaction;
import com.werewolf.model.MatchParticipant;
import com.werewolf.model.RoleStat;
import com.werewolf.model.User;
import com.werewolf.repo.GoldTransactionRepository;
import com.werewolf.repo.MatchParticipantRepository;
import com.werewolf.repo.RoleStatRepository;
import com.werewolf.repo.UserRepository;
import com.werewolf.service.AuthService;
import com.werewolf.service.DecorationService;
import com.werewolf.service.ProfileService;
import com.werewolf.service.VipService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final AuthService authService;
    private final UserRepository userRepo;
    private final ProfileService profileService;
    private final DecorationService decoration;
    private final GoldTransactionRepository goldRepo;
    private final MatchParticipantRepository partRepo;
    private final RoleStatRepository roleStatRepo;
    private final VipService vipService;

    public UserController(AuthService authService, UserRepository userRepo, ProfileService profileService,
                          DecorationService decoration, GoldTransactionRepository goldRepo,
                          MatchParticipantRepository partRepo, RoleStatRepository roleStatRepo, VipService vipService) {
        this.authService = authService;
        this.userRepo = userRepo;
        this.profileService = profileService;
        this.decoration = decoration;
        this.goldRepo = goldRepo;
        this.partRepo = partRepo;
        this.roleStatRepo = roleStatRepo;
        this.vipService = vipService;
    }

    public record NicknameBody(String nickname) {}
    public record EquipBody(long itemDefId) {}
    public record UnequipBody(String type) {}

    @GetMapping("/profile")
    public ResponseEntity<?> profile(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        User user = u.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getId());
        m.put("username", user.getUsername());
        m.put("nickname", user.getNickname());
        m.put("level", user.getLevel());
        m.put("exp", user.getExp());
        m.put("gold", user.getGold());
        m.put("birthday", user.getBirthday());
        m.put("location", user.getLocation());
        m.put("regLocation", user.getRegLocation());
        m.put("signature", user.getSignature());
        m.put("createdAt", user.getCreatedAt() == null ? null : user.getCreatedAt().toString());
        m.put("lastOnlineAt", user.getLastOnlineAt() == null ? null : user.getLastOnlineAt().toString());
        m.put("onlineSeconds", user.getOnlineSeconds());
        m.put("lastCheckIn", user.getLastCheckIn() == null ? null : user.getLastCheckIn().toString());
        m.put("checkinStreak", user.getCheckinStreak());
        m.put("voiceGender", user.getVoiceGender());
        int vlv = vipService.effectiveLevel(user);
        m.put("vipLevel", vlv);
        m.put("vipExpireAt", user.getVipExpireAt() == null ? null : user.getVipExpireAt().toString());
        m.put("vipTitle", VipService.tier(vlv) == null ? null : VipService.tier(vlv).title);
        m.putAll(decoration.of(user));
        return ResponseEntity.ok(m);
    }

    public record VoiceGenderBody(String gender) {}

    @PostMapping("/voice-gender")
    public ResponseEntity<?> voiceGender(@RequestHeader(value = "Authorization", required = false) String auth,
                                         @RequestBody VoiceGenderBody body) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        String g = body.gender() == null ? "" : body.gender().trim().toUpperCase();
        if (!g.isEmpty() && !g.equals("M") && !g.equals("F")) return err("性别仅支持 男/女");
        User user = u.get();
        user.setVoiceGender(g.isEmpty() ? null : g);
        userRepo.save(user);
        return ResponseEntity.ok(Map.of("ok", true, "voiceGender", user.getVoiceGender() == null ? "" : user.getVoiceGender()));
    }

    public record ProfileInfoBody(String birthday, String location, String regLocation, String signature) {}

    public record QqBody(String qq) {}

    /**
     * 设置/补填 QQ 号：保存并尝试拉取 QQ 头像；首次补填奖励 1000 金币（一次性）。
     * 返回 {ok, rewarded, gold_balance, avatarUrl}；错误返回 {error}。
     */
    @PostMapping("/qq")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> setQq(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @RequestBody QqBody body) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        User user = u.get();
        Object[] r = profileService.setQq(user, body.qq());
        String error = (String) r[0];
        if (error != null) return err(error);
        User fresh = userRepo.findById(user.getId()).orElse(user);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true);
        out.put("rewarded", r[1]);
        out.put("qq", fresh.getQq());
        out.put("avatarUrl", fresh.getAvatarUrl());
        out.put("gold_balance", fresh.getGold());
        out.put("needQq", false);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/profile-info")
    public ResponseEntity<?> profileInfo(@RequestHeader(value = "Authorization", required = false) String auth,
                                         @RequestBody ProfileInfoBody body) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        User user = u.get();
        if (body.birthday() != null) user.setBirthday(body.birthday().trim());
        if (body.location() != null) user.setLocation(clamp(body.location(), 32));
        if (body.regLocation() != null) user.setRegLocation(clamp(body.regLocation(), 32));
        if (body.signature() != null) user.setSignature(clamp(body.signature(), 60));
        userRepo.save(user);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @Transactional
    @PostMapping("/checkin")
    public ResponseEntity<?> checkin(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        User user = u.get();
        LocalDate today = LocalDate.now();
        LocalDate y = today.minusDays(1);
        int streak = y.equals(user.getLastCheckIn()) ? user.getCheckinStreak() + 1 : 1;
        long gold = 20 + Math.min(streak, 7) * 5;   // 连续签到加成
        long exp = 15 + Math.min(streak, 7) * 3;
        int bonusPct = vipService.checkinBonusPct(user);
        if (bonusPct > 0) { gold = gold * (100 + bonusPct) / 100; exp = exp * (100 + bonusPct) / 100; }
        // 原子签到：数据库层条件更新（仅当天未签到时生效），返回受影响行数；0 表示今天已签到
        int updated = userRepo.checkIn(user.getId(), today, gold, exp, streak);
        if (updated == 0) return err("今天已经签到过啦");
        // 重新读取最新数据（金币/经验已在库中累加），再处理升级
        User fresh = userRepo.findById(user.getId()).orElseThrow();
        boolean leveled = false;
        while (fresh.getExp() >= expNeeded(fresh.getLevel()) && fresh.getLevel() < SettlementService.MAX_LEVEL) {
            fresh.setExp(fresh.getExp() - expNeeded(fresh.getLevel()));
            fresh.setLevel(fresh.getLevel() + 1);
            leveled = true;
        }
        if (leveled) userRepo.save(fresh);
        GoldTransaction gt = new GoldTransaction();
        gt.setUserId(fresh.getId()); gt.setDelta(gold); gt.setBalanceAfter(fresh.getGold());
        gt.setReason("每日签到 第" + streak + "天");
        goldRepo.save(gt);
        return ResponseEntity.ok(Map.of("ok", true, "streak", streak, "gold", gold, "exp", exp, "vipBonusPct", bonusPct,
                "level", fresh.getLevel(), "gold_balance", fresh.getGold()));
    }

    @GetMapping("/checkin-status")
    public ResponseEntity<?> checkinStatus(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        User user = u.get();
        boolean done = LocalDate.now().equals(user.getLastCheckIn());
        return ResponseEntity.ok(Map.of("doneToday", done, "streak", user.getCheckinStreak()));
    }

    @GetMapping("/rolestats")
    public ResponseEntity<?> roleStats(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        List<Map<String, Object>> out = new ArrayList<>();
        for (RoleStat r : roleStatRepo.findByUserId(u.get().getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", r.getRoleName());
            m.put("games", r.getGames());
            m.put("wins", r.getWins());
            m.put("seconds", r.getSeconds());
            out.add(m);
        }
        out.sort((a, b) -> Integer.compare((int) b.get("games"), (int) a.get("games")));
        return ResponseEntity.ok(out);
    }

    private String clamp(String s, int max) { if (s == null) return null; s = s.trim(); return s.length() > max ? s.substring(0, max) : s; }

    private static long expNeeded(int level) { return (long) Math.floor(100 * Math.pow(level, 1.5)); }

    @PostMapping("/nickname")
    public ResponseEntity<?> nickname(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestBody NicknameBody body) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        String error = profileService.changeNickname(u.get(), body.nickname());
        if (error != null) return err(error);
        return ResponseEntity.ok(AuthController.view(fresh(u.get().getId())));
    }

    @PostMapping("/avatar")
    public ResponseEntity<?> avatar(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestParam("file") MultipartFile file) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        try {
            String error = profileService.uploadAvatar(u.get(), file.getBytes(), file.getContentType());
            if (error != null) return err(error);
            return ResponseEntity.ok(AuthController.view(fresh(u.get().getId())));
        } catch (IOException e) {
            return err("上传失败");
        }
    }

    @PostMapping("/equip")
    public ResponseEntity<?> equip(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @RequestBody EquipBody body) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        String error = profileService.equip(u.get(), body.itemDefId());
        if (error != null) return err(error);
        return ResponseEntity.ok(AuthController.view(fresh(u.get().getId())));
    }

    @PostMapping("/unequip")
    public ResponseEntity<?> unequip(@RequestHeader(value = "Authorization", required = false) String auth,
                                     @RequestBody UnequipBody body) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        profileService.unequip(u.get(), body.type());
        return ResponseEntity.ok(AuthController.view(fresh(u.get().getId())));
    }

    @GetMapping("/transactions")
    public ResponseEntity<?> transactions(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        List<Map<String, Object>> list = new ArrayList<>();
        for (GoldTransaction t : goldRepo.findByUserIdOrderByIdDesc(u.get().getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("delta", t.getDelta());
            m.put("balanceAfter", t.getBalanceAfter());
            m.put("reason", t.getReason());
            m.put("time", t.getCreatedAt().toString());
            list.add(m);
        }
        return ResponseEntity.ok(list);
    }

    public record CarryBody(Long itemDefId) {}

    @PostMapping("/carry")
    public ResponseEntity<?> carry(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @RequestBody CarryBody body) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        String error = profileService.carryItem(u.get(), body.itemDefId());
        if (error != null) return err(error);
        return ResponseEntity.ok(Map.of("ok", true, "carryItemId", u.get().getCarryItemId() == null ? 0 : u.get().getCarryItemId()));
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        List<MatchParticipant> all = partRepo.findByUserIdOrderByIdDesc(u.get().getId());
        int total = all.size(), win = 0, wolfN = 0, wolfW = 0, goodN = 0, goodW = 0;
        for (MatchParticipant m : all) {
            if (m.isWon()) win++;
            if ("WOLF".equals(m.getFaction())) { wolfN++; if (m.isWon()) wolfW++; }
            else { goodN++; if (m.isWon()) goodW++; }
        }
        Map<String, Object> agg = new LinkedHashMap<>();
        agg.put("total", total);
        agg.put("winRate", total == 0 ? 0 : Math.round(win * 1000.0 / total) / 10.0);
        agg.put("wolfGames", wolfN);
        agg.put("wolfWinRate", wolfN == 0 ? 0 : Math.round(wolfW * 1000.0 / wolfN) / 10.0);
        agg.put("goodGames", goodN);
        agg.put("goodWinRate", goodN == 0 ? 0 : Math.round(goodW * 1000.0 / goodN) / 10.0);
        List<Map<String, Object>> recent = new ArrayList<>();
        for (int i = 0; i < Math.min(20, all.size()); i++) {
            MatchParticipant m = all.get(i);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("gameId", m.getGameId());
            r.put("role", m.getRole());
            r.put("faction", m.getFaction());
            r.put("won", m.isWon());
            r.put("survived", m.isSurvived());
            r.put("mvp", m.isMvp());
            r.put("day", m.getDay());
            recent.add(r);
        }
        agg.put("recent", recent);
        return ResponseEntity.ok(agg);
    }

    private User fresh(long id) { return userRepo.findById(id).orElseThrow(); }

    private Optional<User> resolve(String auth) {
        return authService.resolve(AuthController.stripBearer(auth));
    }

    private ResponseEntity<?> err(String msg) { return ResponseEntity.badRequest().body(Map.of("error", msg)); }
}
