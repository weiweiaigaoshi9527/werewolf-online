package com.werewolf.repo;

import com.werewolf.model.LoginSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoginSessionRepository extends JpaRepository<LoginSession, Long> {
    Optional<LoginSession> findByTokenHash(String tokenHash);
    void deleteByTokenHash(String tokenHash);
    void deleteByUserId(Long userId);
    /** 只撤销某用户某个「会话域」的持久凭证（app=玩家端 / admin=后台），使两端可同时登录。 */
    void deleteByUserIdAndScope(Long userId, String scope);
    long deleteByExpireAtBefore(java.time.LocalDateTime time);
}
