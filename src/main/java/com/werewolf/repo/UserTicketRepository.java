package com.werewolf.repo;

import com.werewolf.model.UserTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserTicketRepository extends JpaRepository<UserTicket, Long> {
    List<UserTicket> findByStatusOrderByIdDesc(String status);
    List<UserTicket> findAllByOrderByIdDesc();
    List<UserTicket> findByReporterIdOrderByIdDesc(Long reporterId);

    /** 删除某用户提交的全部工单（账号级联删除用）。 */
    void deleteByReporterId(Long reporterId);
}
