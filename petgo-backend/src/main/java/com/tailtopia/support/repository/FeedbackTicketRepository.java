package com.tailtopia.support.repository;

import com.tailtopia.support.domain.FeedbackTicket;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 客服工单主表读写（Story 4.1）。用户建/查（本 story）+ admin 队列（4-4/4-7）。
 */
public interface FeedbackTicketRepository extends JpaRepository<FeedbackTicket, Long> {

    /** 详情：不可枚举 token 查（owner 校验在 service）。 */
    Optional<FeedbackTicket> findByTicketToken(String ticketToken);

    /** 我的工单列表：本人工单，created_at 倒序。 */
    List<FeedbackTicket> findByUserIdOrderByCreatedAtDesc(long userId);
}
