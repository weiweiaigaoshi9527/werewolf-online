package com.werewolf.repo;

import com.werewolf.model.ForumReply;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ForumReplyRepository extends JpaRepository<ForumReply, Long> {
    List<ForumReply> findByPostIdAndHiddenFalseOrderByIdAsc(Long postId);

    /** 某帖全部回复（含已隐藏）：删帖时级联清理用，避免隐藏回复成为孤儿数据。 */
    List<ForumReply> findByPostIdOrderByIdAsc(Long postId);

    /** 全部回复数（含隐藏）。 */
    long countByPostId(Long postId);

    /** 仅未隐藏回复数：与详情页展示口径一致。 */
    long countByPostIdAndHiddenFalse(Long postId);
}
