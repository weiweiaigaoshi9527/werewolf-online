package com.werewolf.repo;

import com.werewolf.model.LoginSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoginSessionRepository extends JpaRepository<LoginSession, Long> {
    Optional<LoginSession> findByTokenHash(String tokenHash);
    void deleteByTokenHash(String tokenHash);
    void deleteByUserId(Long userId);
    long deleteByExpireAtBefore(java.time.LocalDateTime time);
}
