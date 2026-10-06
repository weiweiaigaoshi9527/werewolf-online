package com.werewolf.repo;

import com.werewolf.model.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoomRepository extends JpaRepository<Room, Long> {
    Optional<Room> findByRoomNo(String roomNo);
    boolean existsByRoomNo(String roomNo);
    long countByStatus(String status);
}
