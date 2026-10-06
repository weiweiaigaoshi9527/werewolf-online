package com.werewolf.service;

import com.werewolf.model.LoginSession;
import com.werewolf.model.User;
import com.werewolf.repo.LoginSessionRepository;
import com.werewolf.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    /** 安全审计日志：登录/封禁/权限变更等关键安全事件。 */
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 会话有效期：12 小时（无操作则过期）。 */
    private static final long SESSION_TTL_HOURS = 12L;

    private final UserRepository userRepository;
    private final com.werewolf.repo.LoginSessionRepository loginSessionRepo;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    /** 上次持久会话过期清理时间（避免每次 resolve 都打库清理）。 */
    private volatile long lastDbPurge = 0L;

    /** 单条会话记录：userId + 过期时间。 */
    private record Session(long userId, LocalDateTime expireAt) {}

    /** token -> 会话（内存会话；带 TTL，服务重启后需重新登录，对局恢复在 M2 由事件流保障） */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public AuthService(UserRepository userRepository, com.werewolf.repo.LoginSessionRepository loginSessionRepo) {
        this.userRepository = userRepository;
        this.loginSessionRepo = loginSessionRepo;
    }

    public record RegisterResult(User user, String error) {}

    /** QQ 号合法性：5-11 位纯数字。 */
    public static boolean validQq(String qq) {
        return qq != null && qq.matches("[1-9][0-9]{4,10}");
    }

    @Transactional // 保证 count() 与 save() 在同一事务内，避免并发注册时出现多个“首个用户”
    public RegisterResult register(String username, String password, String nickname) {
        if (username == null || username.isBlank()) {
            return new RegisterResult(null, "用户名不能为空");
        }
        if (!username.matches("[A-Za-z0-9_]{3,16}")) {
            return new RegisterResult(null, "用户名需为 3-16 位字母/数字/下划线");
        }
        if (password == null || password.length() < 6 || password.length() > 64) {
            return new RegisterResult(null, "密码长度需为 6-64 位");
        }
        String nick = (nickname == null || nickname.isBlank()) ? username : nickname.trim();
        if (nick.length() > 16) {
            return new RegisterResult(null, "昵称最长 16 个字符");
        }
        if (userRepository.existsByUsername(username)) {
            return new RegisterResult(null, "用户名已被注册");
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(password));
        user.setNickname(nick);
        // 首个注册者自动成为管理员，保证系统里一定有人能进后台（这是既定的产品设计，予以保留）
        if (userRepository.count() == 0) user.setAdmin(true);
        return new RegisterResult(userRepository.save(user), null);
    }

    public record LoginResult(String token, User user, String error) {}

    public LoginResult login(String username, String password) {
        return login(username, password, 0);
    }

    /** rememberDays>0 时签发持久化凭证（服务重启后仍有效），0 则仅本次内存会话。 */
    @Transactional
    public LoginResult login(String username, String password, int rememberDays) {
        Optional<User> found = username == null ? Optional.empty()
                : userRepository.findByUsername(username);
        if (found.isEmpty() || !encoder.matches(password == null ? "" : password, found.get().getPasswordHash())) {
            // 安全审计：登录失败。只记录用户名与失败原因，严禁记录密码明文。
            String reason = found.isEmpty() ? "用户不存在" : "密码错误";
            log.warn("登录失败 username={} 原因={}", username, reason);
            return new LoginResult(null, null, "用户名或密码错误");
        }
        if (found.get().isBanned()) {
            // 安全审计：被封禁账号仍尝试登录。
            log.warn("封禁账号尝试登录 userId={}", found.get().getId());
            return new LoginResult(null, null, "该账号已被封禁");
        }
        // 登录成功后回收该用户所有旧 token，再签发新 token（单会话、防旧 token 继续有效）
        long userId = found.get().getId();
        revokeSessions(userId);
        String token = newToken(userId);
        if (rememberDays > 0) {
            // 持久化凭证：存 token 摘要（不存明文），服务重启后仍有效
            LoginSession s = new LoginSession();
            s.setTokenHash(sha256(token));
            s.setUserId(userId);
            s.setExpireAt(LocalDateTime.now().plusDays(Math.min(90, Math.max(1, rememberDays))));
            loginSessionRepo.save(s);
            log.info("签发持久登录凭证 userId={} 保留{}天", userId, rememberDays);
        }
        // 安全审计：登录成功。
        log.info("登录成功 userId={} username={}", userId, username);
        return new LoginResult(token, found.get(), null);
    }

    /* ---------- 管理员维护操作 ---------- */

    /** 重置某用户密码（后台管理员用）。 */
    public void resetPassword(long userId, String newPassword) {
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 64) {
            throw new IllegalArgumentException("密码长度需为 6-64 位");
        }
        userRepository.findById(userId).ifPresent(u -> {
            u.setPasswordHash(encoder.encode(newPassword));
            userRepository.save(u);
            // 安全审计：管理员重置密码（方法签名不含操作者，只记录目标；严禁记录新密码）。
            log.info("管理员重置密码 userId={}", userId);
        });
    }

    public Optional<User> setAdmin(long userId, boolean admin) {
        Optional<User> u = userRepository.findById(userId);
        u.ifPresent(x -> {
            x.setAdmin(admin);
            userRepository.save(x);
            // 安全审计：设为管理员 / 取消管理员（方法签名不含操作者，只记录目标）。
            log.info(admin ? "管理员设为管理员 userId={}" : "管理员取消管理员 userId={}", userId);
        });
        return u;
    }

    public Optional<User> setBanned(long userId, boolean banned) {
        Optional<User> u = userRepository.findById(userId);
        u.ifPresent(x -> {
            x.setBanned(banned);
            userRepository.save(x);
            if (banned) revokeSessions(userId); // 封禁即踢下线
            // 安全审计：封禁 / 解封（方法签名不含操作者，只记录目标）。
            log.info(banned ? "管理员封禁用户 userId={}" : "管理员解封用户 userId={}", userId);
        });
        return u;
    }

    /** 管理员以某用户身份签发一个登录 token（模拟登录 / 以该账号进入）。 */
    public String issueToken(long userId) {
        return newToken(userId);
    }

    @Transactional
    public Optional<User> resolve(String token) {
        if (token == null) return Optional.empty();
        lazyCleanup(); // 惰性清理：避免内存中过期会话无限增长
        Session s = sessions.get(token);
        if (s == null) {
            // 内存未命中 → 查持久化凭证（玩家选择“保留登录 X 天”的会话，服务重启后仍有效）
            Optional<LoginSession> db = loginSessionRepo.findByTokenHash(sha256(token));
            if (db.isEmpty()) return Optional.empty();
            LoginSession ls = db.get();
            if (ls.getExpireAt().isBefore(LocalDateTime.now())) {
                loginSessionRepo.deleteByTokenHash(ls.getTokenHash());
                return Optional.empty();
            }
            s = new Session(ls.getUserId(), ls.getExpireAt());
            sessions.put(token, s);
            maybePurgeDb();
        }
        // 过期则移除并视为无效
        if (s.expireAt().isBefore(LocalDateTime.now())) {
            sessions.remove(token);
            return Optional.empty();
        }
        // 命中即滑动续期
        sessions.put(token, new Session(s.userId(), LocalDateTime.now().plusHours(SESSION_TTL_HOURS)));
        return userRepository.findById(s.userId());
    }

    @Transactional
    public void logout(String token) {
        if (token != null) sessions.remove(token);
        if (token != null) loginSessionRepo.deleteByTokenHash(sha256(token));
    }

    /** 撤销某用户全部会话（封禁/删除账号/重新登录时踢下线）。 */
    public void revokeSessions(long userId) {
        sessions.entrySet().removeIf(e -> e.getValue().userId() == userId);
        loginSessionRepo.deleteByUserId(userId);
    }

    /** token 摘要（服务端只存摘要，不存明文）。 */
    private String sha256(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 每小时至多一次：清理过期的持久化凭证。 */
    private void maybePurgeDb() {
        long now = System.currentTimeMillis();
        if (now - lastDbPurge < 3_600_000L) return;
        lastDbPurge = now;
        try {
            loginSessionRepo.deleteByExpireAtBefore(LocalDateTime.now());
        } catch (Exception ignored) {
        }
    }

    public LocalDateTime sessionTime() { return LocalDateTime.now(); }

    /** 生成一个新 token 并登记会话。 */
    private String newToken(long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new Session(userId, LocalDateTime.now().plusHours(SESSION_TTL_HOURS)));
        return token;
    }

    /**
     * 惰性清理过期会话。项目主类未开启 @EnableScheduling，
     * 故此处不引入 @Scheduled（避免注解不生效），改为在 resolve 时顺带清理。
     */
    private void lazyCleanup() {
        LocalDateTime now = LocalDateTime.now();
        sessions.entrySet().removeIf(e -> e.getValue().expireAt().isBefore(now));
    }
}
