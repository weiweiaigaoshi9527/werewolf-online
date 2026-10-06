package com.werewolf.repo;

import com.werewolf.model.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    List<Inventory> findByUserId(Long userId);
    Optional<Inventory> findByUserIdAndItemDefId(Long userId, Long itemDefId);
    boolean existsByUserIdAndItemDefId(Long userId, Long itemDefId);

    /** 删除某用户的全部背包条目（账号级联删除用）。 */
    void deleteByUserId(Long userId);
}
