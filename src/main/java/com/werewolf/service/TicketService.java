package com.werewolf.service;

import com.werewolf.model.TicketReply;
import com.werewolf.model.User;
import com.werewolf.model.UserTicket;
import com.werewolf.repo.TicketReplyRepository;
import com.werewolf.repo.UserRepository;
import com.werewolf.repo.UserTicketRepository;
import com.werewolf.ws.WsPush;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 工单系统：用户建单/追问，管理员回复/流转状态。客服类工单（category=客服）会由在线客服自动答复，必要时转人工。 */
@Service
public class TicketService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TicketService.class);

    /** 客服类工单的分类名（前端「在线客服」入口用它建单）。 */
    public static final String SUPPORT_CATEGORY = "客服";
    /** 等待人工客服接手的状态。 */
    public static final String STATUS_WAITING_HUMAN = "WAITING_HUMAN";

    private final UserTicketRepository repo;
    private final TicketReplyRepository replies;
    private final UserRepository users;
    private final WsPush push;
    private final com.werewolf.ai.AiSupportService support;

    /** 客服自动回复线程池：AI 调用耗时数秒，不能阻塞用户的 HTTP 请求。 */
    private final java.util.concurrent.ScheduledExecutorService supportExec =
            java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "ww-support");
                t.setDaemon(true);
                return t;
            });

    public TicketService(UserTicketRepository repo, TicketReplyRepository replies, UserRepository users, WsPush push,
                         com.werewolf.ai.AiSupportService support) {
        this.repo = repo;
        this.replies = replies;
        this.users = users;
        this.push = push;
        this.support = support;
    }

    /** 是否客服类工单。 */
    public static boolean isSupport(UserTicket t) {
        return t != null && (SUPPORT_CATEGORY.equals(t.getCategory()) || "SUPPORT".equals(t.getKind()));
    }

    /** 是否已有真人管理员在该工单里回复过（有则客服不再自动答复，避免和人工抢话）。 */
    private boolean humanJoined(long ticketId) {
        for (TicketReply r : replies.findByTicketIdOrderByIdAsc(ticketId)) {
            if (r.isAdmin() && !r.isAi()) return true;
        }
        return false;
    }

    @Transactional
    public UserTicket create(long reporterId, String category, String title, String content, String kind, Long targetId, String roomNo) {
        UserTicket t = new UserTicket();
        t.setReporterId(reporterId);
        String cat = (category == null || category.isBlank()) ? "问题" : category.trim();
        t.setCategory(cat);
        t.setKind("举报".equals(cat) ? "REPORT" : "FEEDBACK");
        t.setTitle(title == null || title.isBlank() ? cat : title.trim());
        t.setTargetId(targetId);
        t.setRoomNo(roomNo);
        t.setContent(content == null ? "" : content.trim());
        t.setStatus("OPEN");
        UserTicket saved = repo.save(t);
        // 客服类工单：玩家发出第一条消息后，由「在线客服」先自动答复一次
        if (isSupport(saved) && saved.getContent() != null && !saved.getContent().isBlank()) {
            scheduleAiReply(saved.getId());
        }
        return saved;
    }

    @Transactional
    public List<Map<String, Object>> listMine(long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserTicket t : repo.findByReporterIdOrderByIdDesc(userId)) out.add(brief(t));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> adminList(String status, String category) {
        List<UserTicket> all = "OPEN".equals(status) || "PROCESSING".equals(status) || "REPLIED".equals(status)
                || "CLOSED".equals(status) || STATUS_WAITING_HUMAN.equals(status)
                ? repo.findByStatusOrderByIdDesc(status) : repo.findAllByOrderByIdDesc();
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserTicket t : all) {
            if (category != null && !category.isBlank() && !category.equals(t.getCategory())) continue;
            Map<String, Object> m = brief(t);
            m.put("reporter", users.findById(t.getReporterId()).map(User::getNickname).orElse("#" + t.getReporterId()));
            m.put("target", t.getTargetId() == null ? null : users.findById(t.getTargetId()).map(User::getNickname).orElse("#" + t.getTargetId()));
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> brief(UserTicket t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("category", t.getCategory());
        m.put("title", t.getTitle());
        m.put("content", t.getContent());
        m.put("status", t.getStatus());
        m.put("roomNo", t.getRoomNo());
        m.put("targetUserId", t.getTargetId());
        m.put("at", t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
        m.put("support", isSupport(t));
        m.put("needsHuman", STATUS_WAITING_HUMAN.equals(t.getStatus()));
        long rc = replies.findByTicketIdOrderByIdAsc(t.getId()).size();
        m.put("replies", rc);
        return m;
    }

    @Transactional
    public Map<String, Object> thread(long ticketId, long viewerId, boolean viewerAdmin) {
        Optional<UserTicket> ot = repo.findById(ticketId);
        if (ot.isEmpty()) return null;
        UserTicket t = ot.get();
        if (!viewerAdmin && !t.getReporterId().equals(viewerId)) return null; // 只有本人或管理员可看
        List<Map<String, Object>> rs = new ArrayList<>();
        for (TicketReply r : replies.findByTicketIdOrderByIdAsc(ticketId)) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("id", r.getId());
            rm.put("author", r.isAi() ? "在线客服"
                    : (r.isAdmin() ? "人工客服"
                    : users.findById(r.getAuthorId()).map(User::getNickname).orElse("#" + r.getAuthorId())));
            rm.put("admin", r.isAdmin());
            rm.put("ai", r.isAi());
            rm.put("content", r.getContent());
            rm.put("at", r.getCreatedAt().toString());
            rs.add(rm);
        }
        Map<String, Object> m = brief(t);
        m.put("reporter", users.findById(t.getReporterId()).map(User::getNickname).orElse(""));
        m.put("target", t.getTargetId() == null ? null : users.findById(t.getTargetId()).map(User::getNickname).orElse("#" + t.getTargetId()));
        m.put("thread", rs);
        return m;
    }

    @Transactional
    public String userReply(long ticketId, long userId, String content) {
        Optional<UserTicket> ot = repo.findById(ticketId);
        if (ot.isEmpty()) return "工单不存在";
        UserTicket t = ot.get();
        if (!t.getReporterId().equals(userId)) return "无权回复";
        if ("CLOSED".equals(t.getStatus())) return "工单已关闭";
        addReply(ticketId, userId, false, content);
        t.setStatus("OPEN"); // 用户追问，重新回到待处理
        repo.save(t);
        // 客服类工单：按玩家意图决定「转人工」还是继续由在线客服自动答复
        if (isSupport(t)) {
            if (support.wantsHuman(content)) {
                escalateToHuman(t, "玩家在对话中要求转人工");
            } else if (!humanJoined(ticketId)) {
                scheduleAiReply(ticketId);
            }
        }
        return null;
    }

    /** 玩家主动点「转人工」。 */
    @Transactional
    public String requestHuman(long ticketId, long userId) {
        Optional<UserTicket> ot = repo.findById(ticketId);
        if (ot.isEmpty()) return "工单不存在";
        UserTicket t = ot.get();
        if (!t.getReporterId().equals(userId)) return "无权操作";
        if ("CLOSED".equals(t.getStatus())) return "工单已关闭";
        if (humanJoined(ticketId) || STATUS_WAITING_HUMAN.equals(t.getStatus())) return null; // 已转人工，幂等
        escalateToHuman(t, "玩家点击转人工");
        return null;
    }

    /* ---------- 在线客服（用户侧聊天面板） ---------- */

    /** 当前用户未关闭的客服会话；没有则返回 null。 */
    @Transactional(readOnly = true)
    public UserTicket findOpenSupport(long userId) {
        for (UserTicket t : repo.findByReporterIdOrderByIdDesc(userId)) {
            if (isSupport(t) && !"CLOSED".equals(t.getStatus())) return t;
        }
        return null;
    }

    /** 客服会话快照（前端聊天面板用）。没有会话时 ticketId 为 null。 */
    @Transactional
    public Map<String, Object> supportSnapshot(long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        UserTicket t = findOpenSupport(userId);
        if (t == null) { m.put("ticketId", null); m.put("thread", List.of()); return m; }
        Map<String, Object> full = thread(t.getId(), userId, false);
        if (full == null) { m.put("ticketId", null); m.put("thread", List.of()); return m; }
        m.put("ticketId", t.getId());
        m.put("status", full.get("status"));
        m.put("needsHuman", full.get("needsHuman"));
        m.put("human", humanJoined(t.getId()));
        m.put("thread", full.get("thread"));
        return m;
    }

    /** 客服会话：玩家发一条消息（没有会话则先建单）。客服答复异步进行，前端随后刷新即可。 */
    @Transactional
    public Map<String, Object> supportSend(long userId, String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) return Map.of("error", "内容不能为空");
        if (text.length() > 2000) text = text.substring(0, 2000);
        UserTicket t = findOpenSupport(userId);
        if (t == null) {
            UserTicket n = new UserTicket();
            n.setReporterId(userId);
            n.setCategory(SUPPORT_CATEGORY);
            n.setKind("SUPPORT");
            n.setTitle("在线客服");
            n.setStatus("OPEN");
            n.setContent(text);            // 首条消息即内容
            t = repo.save(n);
            if (support.wantsHuman(text)) escalateToHuman(t, "玩家首条即要求转人工");
            else scheduleAiReply(t.getId());
        } else {
            String err = userReply(t.getId(), userId, text);
            if (err != null) return Map.of("error", err);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("ticketId", t.getId());
        return m;
    }

    /** 客服会话：玩家请求转人工。 */
    @Transactional
    public String supportHuman(long userId) {
        UserTicket t = findOpenSupport(userId);
        if (t == null) return "还没有客服会话";
        return requestHuman(t.getId(), userId);
    }

    /**
     * 转人工：写入一条中性承接话术、把状态置为「等待人工」，并实时通知在线管理员。
     * 之后该工单不再自动答复，直到管理员回复或玩家再次追问。
     */
    private void escalateToHuman(UserTicket t, String why) {
        addReply(t.getId(), 0L, true, com.werewolf.ai.AiSupportService.HUMAN_TAKEOVER, true);
        t.setStatus(STATUS_WAITING_HUMAN);
        repo.save(t);
        log.info("工单 {} 转人工（{}）", t.getId(), why);
        push.send(t.getReporterId(), "support.update", Map.of("ticketId", t.getId(), "status", STATUS_WAITING_HUMAN));
        for (User a : users.findByAdminTrue()) {
            push.send(a.getId(), "support.escalated",
                    Map.of("ticketId", t.getId(), "reporter", t.getReporterId()));
        }
    }

    /**
     * 异步生成一条「在线客服」回复并落库。
     * 延迟 600ms 再执行，确保外层事务已提交、能读到玩家刚发出的那条消息。
     */
    private void scheduleAiReply(long ticketId) {
        supportExec.schedule(() -> {
            try {
                Optional<UserTicket> ot = repo.findById(ticketId);
                if (ot.isEmpty()) return;
                UserTicket t = ot.get();
                if (!isSupport(t) || "CLOSED".equals(t.getStatus())) return;
                if (humanJoined(ticketId)) return;                  // 人工已接手，不再自动回复
                if (STATUS_WAITING_HUMAN.equals(t.getStatus())) return;

                List<TicketReply> all = replies.findByTicketIdOrderByIdAsc(ticketId);
                // 需要回应的「玩家最新一条」：首条消息存在工单正文里（此时还没有任何回复记录）
                String latest;
                List<TicketReply> prior;
                if (all.isEmpty()) {
                    latest = t.getContent();
                    prior = List.of();
                } else {
                    TicketReply last = all.get(all.size() - 1);
                    // 最后一条已是客服/人工发的 → 不需要再回（避免自问自答）
                    if (last.isAi() || last.isAdmin()) return;
                    latest = last.getContent();
                    prior = all.subList(0, all.size() - 1);
                }
                if (latest == null || latest.isBlank()) return;

                List<String[]> history = new ArrayList<>();
                for (TicketReply r : prior) {
                    String who = r.isAi() ? "在线客服"
                            : (r.isAdmin() ? "人工客服"
                            : users.findById(r.getAuthorId()).map(User::getNickname).orElse("玩家"));
                    history.add(new String[]{who, r.getContent() == null ? "" : r.getContent()});
                }

                String text = support.reply(history, latest);
                if (text == null || text.isBlank()) text = com.werewolf.ai.AiSupportService.FALLBACK;
                addReply(ticketId, 0L, true, text, true);
                log.info("工单 {} 已由在线客服自动答复", ticketId);
                push.send(t.getReporterId(), "support.update", Map.of("ticketId", ticketId, "status", t.getStatus()));
            } catch (Exception e) {
                log.warn("工单 {} 客服自动答复异常：{}", ticketId, e.toString());
            }
        }, 600, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Transactional
    public String adminReply(long ticketId, long adminId, String content, String status) {
        Optional<UserTicket> ot = repo.findById(ticketId);
        if (ot.isEmpty()) return "工单不存在";
        UserTicket t = ot.get();
        addReply(ticketId, adminId, true, content);
        if (status != null && !status.isBlank()) t.setStatus(status);
        else t.setStatus("REPLIED");
        repo.save(t);
        push.send(t.getReporterId(), "ticket.update", Map.of("id", ticketId, "status", t.getStatus()));
        return null;
    }

    @Transactional
    public String setStatus(long ticketId, String status) {
        Optional<UserTicket> ot = repo.findById(ticketId);
        if (ot.isEmpty()) return "工单不存在";
        UserTicket t = ot.get();
        t.setStatus(status);
        repo.save(t);
        push.send(t.getReporterId(), "ticket.update", Map.of("id", ticketId, "status", status));
        return null;
    }

    private void addReply(long ticketId, long authorId, boolean admin, String content) {
        addReply(ticketId, authorId, admin, content, false);
    }

    /** @param ai 是否为「在线客服」自动生成（authorId 用 0 作占位） */
    private void addReply(long ticketId, long authorId, boolean admin, String content, boolean ai) {
        TicketReply r = new TicketReply();
        r.setTicketId(ticketId); r.setAuthorId(authorId); r.setAdmin(admin); r.setAi(ai);
        r.setContent(content == null ? "" : content.trim());
        replies.save(r);
    }
}
