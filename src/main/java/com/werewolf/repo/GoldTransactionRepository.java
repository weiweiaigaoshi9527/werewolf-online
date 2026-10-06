package com.werewolf.repo;

import com.werewolf.model.GoldTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GoldTransactionRepository extends JpaRepository<GoldTransaction, Long> {
    List<GoldTransaction> findByUserIdOrderByIdDesc(Long userId);

    /** 删除某用户的全部金币流水（账号级联删除用）。 */
    void deleteByUserId(Long userId);
}
