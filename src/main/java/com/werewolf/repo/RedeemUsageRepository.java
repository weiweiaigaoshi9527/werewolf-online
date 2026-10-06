package com.werewolf.repo;

import com.werewolf.model.RedeemUsage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RedeemUsageRepository extends JpaRepository<RedeemUsage, Long> {
    boolean existsByCodeIdAndUserId(Long codeId, Long userId);

    /** 删除某用户的全部兑换记录（账号级联删除用）。 */
    void deleteByUserId(Long userId);
}
