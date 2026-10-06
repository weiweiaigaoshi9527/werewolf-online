package com.werewolf.api;

import com.werewolf.model.ForumPost;
import com.werewolf.model.ForumReply;
import com.werewolf.model.User;
import com.werewolf.repo.UserRepository;
import com.werewolf.repo.ForumPostRepository;
import com.werewolf.repo.ForumReplyRepository;
import com.werewolf.repo.UserRepository;
import com.werewolf.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 论坛：帖子列表 / 详情 / 发帖 / 回复 / 删除；管理员可置顶/隐藏。 */
@RestController
@RequestMapping("/api/forum")
public class ForumController {

    private final AuthService authService;
    private final UserRepository users;
    private final ForumPostRepository posts;
    private final ForumReplyRepository replies;

    public ForumController(AuthService authService, UserRepository users, ForumPostRepository posts, ForumReplyRepository replies) {
        this.authService = authService;
        this.users = users;
        this.posts = posts;
        this.replies = replies;
    }

    public record PostBody(String title, String content, String category) {}
    public record ReplyBody(String content) {}
    public record FlagBody(boolean value) {}

    @GetMapping
    public ResponseEntity<?> list() {
        // 一次性取出全部帖子，再批量查作者昵称，避免 brief() 里逐帖查库造成的 N+1
        List<ForumPost> all = posts.findByHiddenFalseOrderByPinnedDescIdDesc();
        Map<Long, String> names = authorNames(all.stream().map(ForumPost::getUserId).toList());
        List<Map<String, Object>> out = new ArrayList<>();
        for (ForumPost p : all) {
            Map<String, Object> m = brief(p, names.get(p.getUserId()));
            // 列表页只返回正文摘要，降低单次响应体（详情接口仍返回全文）
            String c = p.getContent();
            if (c != null && c.length() > 200) m.put("content", c.substring(0, 200) + "…");
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /** 批量查询作者昵称，避免列表渲染时的 N+1 查询。 */
    private Map<Long, String> authorNames(List<Long> ids) {
        Map<Long, String> map = new HashMap<>();
        if (ids.isEmpty()) return map;
        for (User u : users.findAllById(new LinkedHashSet<>(ids))) map.put(u.getId(), u.getNickname());
        return map;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable long id) {
        Optional<ForumPost> op = posts.findById(id);
        if (op.isEmpty() || op.get().isHidden()) return bad("帖子不存在或已隐藏");
        ForumPost p = op.get();
        Map<String, Object> m = brief(p, name(p.getUserId()));
        List<ForumReply> allReplies = replies.findByPostIdAndHiddenFalseOrderByIdAsc(id);
        Map<Long, String> names = authorNames(allReplies.stream().map(ForumReply::getUserId).toList());
        List<Map<String, Object>> rs = new ArrayList<>();
        for (ForumReply r : allReplies) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("id", r.getId()); rm.put("content", r.getContent()); rm.put("at", r.getCreatedAt().toString());
            rm.put("userId", r.getUserId()); rm.put("author", names.getOrDefault(r.getUserId(), "#" + r.getUserId()));
            rs.add(rm);
        }
        m.put("replies", rs);
        return ResponseEntity.ok(m);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody PostBody body) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        String title = trim(body.title(), 80), content = trim(body.content(), 4000);
        if (title.isEmpty() || content.isEmpty()) return bad("标题和内容不能为空");
        ForumPost p = new ForumPost();
        p.setUserId(u.get().getId()); p.setTitle(title); p.setContent(content);
        String cat = trim(body.category(), 16); p.setCategory(cat.isEmpty() ? "综合" : cat);
        p = posts.save(p);
        return ResponseEntity.ok(Map.of("ok", true, "id", p.getId()));
    }

    @PostMapping("/{id}/reply")
    @Transactional
    public ResponseEntity<?> reply(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @PathVariable long id, @RequestBody ReplyBody body) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        Optional<ForumPost> op = posts.findById(id);
        if (op.isEmpty() || op.get().isHidden()) return bad("帖子不存在");
        String content = trim(body.content(), 2000);
        if (content.isEmpty()) return bad("回复不能为空");
        ForumReply r = new ForumReply();
        r.setPostId(id); r.setUserId(u.get().getId()); r.setContent(content);
        replies.save(r);
        ForumPost p = op.get();
        // 回复计数与详情展示口径保持一致（都只统计未隐藏回复），避免计数与实际可见条数不符
        p.setReplyCount((int) replies.countByPostIdAndHiddenFalse(id));
        posts.save(p);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/delete")
    @Transactional
    public ResponseEntity<?> delete(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable long id) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        Optional<ForumPost> op = posts.findById(id);
        if (op.isEmpty()) return bad("帖子不存在");
        if (!(op.get().getUserId().equals(u.get().getId()) || u.get().isAdmin())) return bad("无权删除");
        posts.deleteById(id);
        // 删除该帖「全部」回复（含已隐藏），避免隐藏回复成为指向已删帖子的孤儿数据
        replies.deleteAll(replies.findByPostIdOrderByIdAsc(id));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/hide")
    public ResponseEntity<?> hide(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @PathVariable long id, @RequestBody FlagBody body) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty() || !u.get().isAdmin()) return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        return posts.findById(id).<ResponseEntity<?>>map(p -> { p.setHidden(body.value()); posts.save(p); return ResponseEntity.ok(Map.of("ok", true)); })
                .orElseGet(() -> bad("帖子不存在"));
    }

    @PostMapping("/{id}/pin")
    public ResponseEntity<?> pin(@RequestHeader(value = "Authorization", required = false) String auth,
                                 @PathVariable long id, @RequestBody FlagBody body) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty() || !u.get().isAdmin()) return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        return posts.findById(id).<ResponseEntity<?>>map(p -> { p.setPinned(body.value()); posts.save(p); return ResponseEntity.ok(Map.of("ok", true)); })
                .orElseGet(() -> bad("帖子不存在"));
    }

    private Map<String, Object> brief(ForumPost p, String author) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId()); m.put("title", p.getTitle()); m.put("content", p.getContent());
        m.put("category", p.getCategory()); m.put("pinned", p.isPinned()); m.put("hidden", p.isHidden());
        m.put("replyCount", p.getReplyCount()); m.put("userId", p.getUserId());
        m.put("author", author == null ? "#" + p.getUserId() : author);
        m.put("at", p.getCreatedAt() == null ? null : p.getCreatedAt().toString());
        return m;
    }

    private String name(long uid) {
        return users.findById(uid).map(User::getNickname).orElse("#" + uid);
    }
    private Optional<User> resolve(String auth) { return authService.resolve(AuthController.stripBearer(auth)); }
    private static String trim(String s, int max) { if (s == null) return ""; s = s.trim(); return s.length() > max ? s.substring(0, max) : s; }
    private ResponseEntity<?> bad(String m) { return ResponseEntity.badRequest().body(Map.of("error", m)); }
}
