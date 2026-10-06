package com.werewolf.repo;

import com.werewolf.model.RoomDuo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoomDuoRepository extends JpaRepository<RoomDuo, Long> {
    List<RoomDuo> findByRoomId(Long roomId);
    void deleteByRoomId(Long roomId);
}
