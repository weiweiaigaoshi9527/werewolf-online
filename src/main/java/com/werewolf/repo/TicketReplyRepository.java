package com.werewolf.repo;

import com.werewolf.model.TicketReply;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TicketReplyRepository extends JpaRepository<TicketReply, Long> {
    List<TicketReply> findByTicketIdOrderByIdAsc(Long ticketId);

    /** 删除一批工单下的全部回复（账号级联删除用）。 */
    void deleteByTicketIdIn(Collection<Long> ticketIds);

    /** 删除某用户作为作者发出的全部回复（账号级联删除用）。 */
    void deleteByAuthorId(Long authorId);
}
