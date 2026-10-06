package com.werewolf.api;

import com.werewolf.model.User;
import com.werewolf.repo.MatchParticipantRepository;
import com.werewolf.repo.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 排行榜（公开）：按胜场 / 金币 / 等级 排出榜。 */
@RestController
@RequestMapping("/api/ranking")
public class RankingController {

    private final UserRepository users;
    private final MatchParticipantRepository matches;

    public RankingController(UserRepository users, MatchParticipantRepository matches) {
        this.users = users;
        this.matches = matches;
    }

    @GetMapping
    public ResponseEntity<?> ranking(@RequestParam(defaultValue = "win") String by,
                                     @RequestParam(defaultValue = "50") int limit) {
        limit = Math.max(5, Math.min(200, limit));
        // 每位玩家的场次/胜场/MVP
        Map<Long, long[]> agg = new HashMap<>();
        for (Object[] row : matches.leaderboard()) {
            long uid = ((Number) row[0]).longValue();
            long games = ((Number) row[1]).longValue();
            long wins = row[2] == null ? 0 : ((Number) row[2]).longValue();
            long mvp = row[3] == null ? 0 : ((Number) row[3]).longValue();
            agg.put(uid, new long[]{games, wins, mvp});
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (User u : users.findAll()) {
            if (u.isBanned()) continue;
            long[] a = agg.getOrDefault(u.getId(), new long[]{0, 0, 0});
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", u.getId());
            m.put("nickname", u.getNickname());
            m.put("level", u.getLevel());
            m.put("gold", u.getGold());
            m.put("games", a[0]);
            m.put("wins", a[1]);
            m.put("mvp", a[2]);
            m.put("winRate", a[0] == 0 ? 0 : Math.round(a[1] * 1000.0 / a[0]) / 10.0);
            list.add(m);
        }
        Comparator<Map<String, Object>> cmp;
        if ("gold".equals(by)) cmp = (x, y) -> Long.compare(((Number) y.get("gold")).longValue(), ((Number) x.get("gold")).longValue());
        else if ("level".equals(by)) cmp = (x, y) -> Integer.compare(((Number) y.get("level")).intValue(), ((Number) x.get("level")).intValue());
        else cmp = (x, y) -> {
            int c = Long.compare(((Number) y.get("wins")).longValue(), ((Number) x.get("wins")).longValue());
            if (c != 0) return c;
            return Long.compare(((Number) y.get("games")).longValue(), ((Number) x.get("games")).longValue());
        };
        list.sort(cmp);
        if (list.size() > limit) list = list.subList(0, limit);
        for (int i = 0; i < list.size(); i++) list.get(i).put("rank", i + 1);
        return ResponseEntity.ok(Map.of("by", by, "list", list));
    }
}
