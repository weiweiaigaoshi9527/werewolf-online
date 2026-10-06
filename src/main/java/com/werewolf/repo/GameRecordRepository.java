package com.werewolf.repo;

import com.werewolf.model.GameRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GameRecordRepository extends JpaRepository<GameRecord, Long> {
    List<GameRecord> findByRoomId(Long roomId);
}
