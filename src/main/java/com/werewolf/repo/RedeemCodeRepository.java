package com.werewolf.repo;

import com.werewolf.model.RedeemCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RedeemCodeRepository extends JpaRepository<RedeemCode, Long> {
    Optional<RedeemCode> findByCode(String code);

    /**
     * 原子占用一次兑换名额：仅当已用次数小于上限时才 +1，返回受影响行数。
     * 返回 0 表示兑换码已被领完（并发下也不会超发）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RedeemCode c SET c.usedCount = c.usedCount + 1 WHERE c.id = :id AND c.usedCount < :maxUses")
    int consumeOne(@Param("id") long id, @Param("maxUses") int maxUses);
}
