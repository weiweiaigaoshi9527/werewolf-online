package com.werewolf.game;

import com.werewolf.model.GoldTransaction;
import com.werewolf.model.Inventory;
import com.werewolf.model.ItemDefinition;
import com.werewolf.model.MatchParticipant;
import com.werewolf.model.RoleStat;
import com.werewolf.model.User;
import com.werewolf.repo.GoldTransactionRepository;
import com.werewolf.repo.InventoryRepository;
import com.werewolf.repo.ItemDefinitionRepository;
import com.werewolf.repo.MatchParticipantRepository;
import com.werewolf.repo.RoleStatRepository;
import com.werewolf.repo.UserRepository;
import com.werewolf.rules.Faction;
import com.werewolf.rules.GameEngine;
import com.werewolf.rules.Player;
import com.werewolf.rules.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 对局结算：给真人玩家发放经验/金币（含双倍卡），判定 MVP，处理升级，写金币流水与战绩。
 * 数值：完赛 20 + 胜利 30 + 存活 10 + MVP 20（经验与金币同规则）。
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);
    private final UserRepository userRepo;
    private final GoldTransactionRepository goldRepo;
    private final InventoryRepository invRepo;
    private final ItemDefinitionRepository itemRepo;
    private final MatchParticipantRepository partRepo;
    private final RoleStatRepository roleStatRepo;

    public SettlementService(UserRepository userRepo, GoldTransactionRepository goldRepo,
                             InventoryRepository invRepo, ItemDefinitionRepository itemRepo,
                             MatchParticipantRepository partRepo, RoleStatRepository roleStatRepo) {
        this.userRepo = userRepo;
        this.goldRepo = goldRepo;
        this.invRepo = invRepo;
        this.itemRepo = itemRepo;
        this.partRepo = partRepo;
        this.roleStatRepo = roleStatRepo;
    }

    public static long expNeeded(int level) { return (long) Math.floor(100 * Math.pow(level, 1.5)); }

    /** 等级上限：1000 级。达到上限后继续累计经验，但不再升级。 */
    public static final int MAX_LEVEL = 1000;

    @Transactional
    public void settle(Long gameId, GameEngine engine, Map<Long, Integer> userToSeat, String roomNo, long seconds, boolean itemMatch) {
        if (engine.winner() == null) return;
        boolean wolfWon = engine.winner() == Faction.WOLF;
        int mvpSeat = pickMvp(engine, wolfWon);

        for (Map.Entry<Long, Integer> e : userToSeat.entrySet()) {
            long uid = e.getKey();
            int seat = e.getValue();
            Player p = engine.bySeat(seat);
            if (p == null) continue;
            boolean won = (p.isWolf() == wolfWon);
            boolean survived = p.alive;
            boolean mvp = (seat == mvpSeat);

            long base = 20 + (won ? 30 : 0) + (survived ? 10 : 0) + (mvp ? 20 : 0);

            Optional<User> found = userRepo.findById(uid);
            if (found.isEmpty()) continue;
            User u = found.get();

            // 双倍积分卡：金币经验 ×2；双倍经验卡：仅经验 ×2（均消耗携带道具，且仅功能道具赛生效）
            long gain = base;
            long expGain = base;
            Long carry = u.getCarryItemId();
            if (carry != null && itemMatch) {
                ItemDefinition item = itemRepo.findById(carry).orElse(null);
                if (item != null && "FUNCTION".equals(item.getType())) {
                    if ("DOUBLE".equals(item.getAsset())) { gain = base * 2; expGain = base * 2; }
                    else if ("EXPDOUBLE".equals(item.getAsset())) { expGain = base * 2; }
                }
                consumeCarry(u, carry);
            }

            u.setGold(u.getGold() + gain);
            GoldTransaction gt = new GoldTransaction();
            gt.setUserId(uid);
            gt.setDelta(gain);
            gt.setBalanceAfter(u.getGold());
            gt.setReason("对局结算 " + roomNo + (won ? "·胜" : "·负") + (mvp ? "·MVP" : "") + (gain > base ? "·双倍" : ""));
            gt.setGameId(gameId);
            goldRepo.save(gt);

            u.setExp(u.getExp() + expGain);
            while (u.getExp() >= expNeeded(u.getLevel()) && u.getLevel() < MAX_LEVEL) {
                u.setExp(u.getExp() - expNeeded(u.getLevel()));
                u.setLevel(u.getLevel() + 1);
            }
            userRepo.save(u);

            // 战绩
            MatchParticipant mp = new MatchParticipant();
            mp.setGameId(gameId); mp.setUserId(uid); mp.setSeat(seat);
            mp.setRole(p.role.cnName()); mp.setFaction(p.isWolf() ? "WOLF" : "GOOD");
            mp.setWon(won); mp.setSurvived(survived); mp.setMvp(mvp); mp.setDay(engine.day());
            partRepo.save(mp);

            // 角色使用统计（场次/胜场/时长）
            String roleName = p.role.cnName();
            RoleStat rs = roleStatRepo.findByUserIdAndRoleName(uid, roleName).orElseGet(() -> {
                RoleStat n = new RoleStat(); n.setUserId(uid); n.setRoleName(roleName); return n;
            });
            rs.setGames(rs.getGames() + 1);
            if (won) rs.setWins(rs.getWins() + 1);
            rs.setSeconds(rs.getSeconds() + Math.max(0, seconds));
            roleStatRepo.save(rs);

            log.info("结算 座位{} {} +{}金/经验{} 胜={} 存活={} MVP={}", seat, u.getNickname(), gain, gain, won, survived, mvp);
        }
    }

    private void consumeCarry(User u, long itemDefId) {
        invRepo.findByUserIdAndItemDefId(u.getId(), itemDefId).ifPresent(inv -> {
            inv.setUsed(true);
            invRepo.save(inv);
        });
        u.setCarryItemId(null);
    }

    /** MVP：获胜阵营存活者中，预言家验狼数优先，其次座位最小。无存活获胜者则返回 0。 */
    private int pickMvp(GameEngine engine, boolean wolfWon) {
        List<Player> winners = new ArrayList<>();
        for (Player p : engine.players()) {
            if (p.alive && (p.isWolf() == wolfWon)) winners.add(p);
        }
        if (winners.isEmpty()) return 0;
        winners.sort((a, b) -> {
            int sa = wolfChecks(engine, a), sb = wolfChecks(engine, b);
            if (sa != sb) return sb - sa;
            return a.seat - b.seat;
        });
        return winners.get(0).seat;
    }

    private int wolfChecks(GameEngine engine, Player p) {
        if (p.role != Role.SEER) return 0;
        int c = 0;
        for (var ev : engine.events()) {
            if (ev.actorSeat() == p.seat && ev.detail() != null && ev.detail().startsWith("SEER_CHECK=WOLF")) c++;
        }
        return c;
    }
}
