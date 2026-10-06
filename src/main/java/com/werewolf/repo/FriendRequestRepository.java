package com.werewolf.repo;

import com.werewolf.model.FriendRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FriendRequestRepository extends JpaRepository<FriendRequest, Long> {
    List<FriendRequest> findByToIdAndStatusOrderByCreatedAtDesc(Long toId, String status);
    List<FriendRequest> findByFromIdOrderByCreatedAtDesc(Long fromId);
    Optional<FriendRequest> findByFromIdAndToIdAndStatus(Long fromId, Long toId, String status);
    long countByToIdAndStatus(Long toId, String status);
    void deleteByFromId(Long fromId);
    void deleteByToId(Long toId);
}
