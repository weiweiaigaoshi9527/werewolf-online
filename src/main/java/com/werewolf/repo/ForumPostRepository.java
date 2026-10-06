package com.werewolf.repo;

import com.werewolf.model.ForumPost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ForumPostRepository extends JpaRepository<ForumPost, Long> {
    List<ForumPost> findByHiddenFalseOrderByPinnedDescIdDesc();
    List<ForumPost> findAllByOrderByIdDesc();
}
