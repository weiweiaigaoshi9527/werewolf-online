package com.werewolf.repo;

import com.werewolf.model.Friendship;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FriendshipRepository extends JpaRepository<Friendship, Long> {
    List<Friendship> findByUserId(Long userId);
    Optional<Friendship> findByUserIdAndFriendId(Long userId, Long friendId);
    boolean existsByUserIdAndFriendId(Long userId, Long friendId);
    void deleteByUserIdAndFriendId(Long userId, Long friendId);
    void deleteByUserId(Long userId);
    void deleteByFriendId(Long friendId);
}
