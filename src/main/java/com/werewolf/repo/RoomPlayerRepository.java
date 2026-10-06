package com.werewolf.repo;

import com.werewolf.model.RoomPlayer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoomPlayerRepository extends JpaRepository<RoomPlayer, Long> {
    List<RoomPlayer> findByRoomIdOrderBySeatNoAsc(Long roomId);
    Optional<RoomPlayer> findByRoomIdAndUserId(Long roomId, Long userId);
    List<RoomPlayer> findByUserId(Long userId);
    long countByRoomId(Long roomId);
    void deleteByRoomIdAndUserId(Long roomId, Long userId);
    void deleteByUserId(Long userId);
    void deleteByRoomId(Long roomId);
}
