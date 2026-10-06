package com.werewolf.repo;

import com.werewolf.model.GameKudos;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface GameKudosRepository extends JpaRepository<GameKudos, Long> {
    List<GameKudos> findByGameId(Long gameId);

    /** 每位玩家在某局收到的各类互动数：行 [toUserId, type, cnt]。 */
    @Query("select k.toUserId, k.type, count(k) from GameKudos k where k.gameId = ?1 group by k.toUserId, k.type")
    List<Object[]> countByGame(Long gameId);

    long countByToUserIdAndType(Long toUserId, String type);

    /** 账号级联删除：清理该用户发出的全部互动记录。 */
    void deleteByFromUserId(Long fromUserId);

    /** 账号级联删除：清理该用户收到的全部互动记录。 */
    void deleteByToUserId(Long toUserId);
}
