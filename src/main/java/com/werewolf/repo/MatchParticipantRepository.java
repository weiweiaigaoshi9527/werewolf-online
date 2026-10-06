package com.werewolf.repo;

import com.werewolf.model.MatchParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MatchParticipantRepository extends JpaRepository<MatchParticipant, Long> {
    List<MatchParticipant> findByUserIdOrderByIdDesc(Long userId);

    /** 删除某用户的全部战绩记录（账号级联删除用）。 */
    void deleteByUserId(Long userId);

    /** 按对局 id 查询全部参与者（回放越权校验用）。 */
    List<MatchParticipant> findByGameId(Long gameId);

    /** 排行榜聚合：每行 [userId, games, wins, mvpCount]。 */
    @Query("select p.userId, count(p), sum(case when p.won = true then 1L else 0L end), sum(case when p.mvp = true then 1L else 0L end) " +
           "from MatchParticipant p group by p.userId")
    List<Object[]> leaderboard();
}
