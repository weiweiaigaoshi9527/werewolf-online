package com.werewolf.repo;

import com.werewolf.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByNickname(String nickname);

    /**
     * 原子签到：仅当今天尚未签到时，才累加金币/经验并更新连签天数与签到日期。
     * 在数据库层用条件更新保证并发安全，返回受影响行数；返回 0 表示今天已经签到过。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.gold = u.gold + :gold, u.exp = u.exp + :exp, " +
           "u.checkinStreak = :streak, u.lastCheckIn = :today " +
           "WHERE u.id = :id AND (u.lastCheckIn IS NULL OR u.lastCheckIn <> :today)")
    int checkIn(@Param("id") long id, @Param("today") LocalDate today,
                @Param("gold") long gold, @Param("exp") long exp, @Param("streak") int streak);

    /** 原子扣减金币（条件更新）：余额不足时不更新，返回 0 表示扣减失败。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.gold = u.gold - :amount WHERE u.id = :id AND u.gold >= :amount")
    int deductGold(@Param("id") long id, @Param("amount") long amount);

    /** 原子增加金币（兑换码发奖等）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.gold = u.gold + :amount WHERE u.id = :id")
    int addGold(@Param("id") long id, @Param("amount") long amount);
}
