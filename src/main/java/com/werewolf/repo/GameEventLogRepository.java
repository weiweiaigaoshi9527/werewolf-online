package com.werewolf.repo;

import com.werewolf.model.GameEventLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GameEventLogRepository extends JpaRepository<GameEventLog, Long> {
    List<GameEventLog> findByGameIdOrderBySeqAsc(Long gameId);
}
