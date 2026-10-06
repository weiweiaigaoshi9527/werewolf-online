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

/** 工单系统：用户建单/追问，管理员回复/流转状态。 */
@Service
public class TicketService {

    private final UserTicketRepository repo;
    private final TicketReplyRepository replies;
    private final UserRepository users;
    private final WsPush push;

    public TicketService(UserTicketRepository repo, TicketReplyRepository replies, UserRepository users, WsPush push) {
        this.repo = repo;
        this.replies = replies;
        this.users = users;
        this.push = push;
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
        return repo.save(t);
    }

    @Transactional
    public List<Map<String, Object>> listMine(long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserTicket t : repo.findByReporterIdOrderByIdDesc(userId)) out.add(brief(t));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> adminList(String status, String category) {
        List<UserTicket> all = "OPEN".equals(status) || "PROCESSING".equals(status) || "REPLIED".equals(status) || "CLOSED".equals(status)
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
            rm.put("author", users.findById(r.getAuthorId()).map(User::getNickname).orElse("#" + r.getAuthorId()));
            rm.put("admin", r.isAdmin());
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
        return null;
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
        TicketReply r = new TicketReply();
        r.setTicketId(ticketId); r.setAuthorId(authorId); r.setAdmin(admin);
        r.setContent(content == null ? "" : content.trim());
        replies.save(r);
    }
}
