package com.werewolf.repo;

import com.werewolf.model.RoleStat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleStatRepository extends JpaRepository<RoleStat, Long> {
    Optional<RoleStat> findByUserIdAndRoleName(Long userId, String roleName);
    List<RoleStat> findByUserId(Long userId);

    /** 删除某用户的全部角色统计（账号级联删除用）。 */
    void deleteByUserId(Long userId);
}
