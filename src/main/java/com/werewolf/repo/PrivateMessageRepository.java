package com.werewolf.repo;

import com.werewolf.model.PrivateMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PrivateMessageRepository extends JpaRepository<PrivateMessage, Long> {

    @Query("select m from PrivateMessage m where (m.fromId = :a and m.toId = :b) or (m.fromId = :b and m.toId = :a) order by m.id asc")
    List<PrivateMessage> findConversation(@Param("a") Long a, @Param("b") Long b);

    long countByToIdAndReadByToFalse(Long toId);

    /** 统计发给 me 的未读消息中，来自某个 peer 的条数（避免加载整个会话）。 */
    long countByToIdAndFromIdAndReadByToFalse(Long toId, Long fromId);

    /** 一次性按来源用户聚合未读数：每行 [fromId, count]，用于好友列表避免逐条统计。 */
    @Query("select m.fromId, count(m) from PrivateMessage m where m.toId = :me and m.readByTo = false group by m.fromId")
    List<Object[]> countUnreadGroupByFrom(@Param("me") Long me);

    void deleteByFromId(Long fromId);
    void deleteByToId(Long toId);

    @Modifying
    @Query("update PrivateMessage m set m.readByTo = true where m.toId = :me and m.fromId = :peer and m.readByTo = false")
    void markRead(@Param("me") Long me, @Param("peer") Long peer);
}
