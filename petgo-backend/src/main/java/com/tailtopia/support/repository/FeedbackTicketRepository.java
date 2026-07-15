package com.tailtopia.support.repository;

import com.tailtopia.support.domain.FeedbackTicket;
import com.tailtopia.support.domain.TicketStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 客服工单主表读写（Story 4.1）。用户建/查（4-1）+ admin 队列/结案（4-7）。
 */
public interface FeedbackTicketRepository extends JpaRepository<FeedbackTicket, Long> {

    /** 详情：不可枚举 token 查（owner 校验在 service）。 */
    Optional<FeedbackTicket> findByTicketToken(String ticketToken);

    /** 我的工单列表：本人工单，created_at 倒序。 */
    List<FeedbackTicket> findByUserIdOrderByCreatedAtDesc(long userId);

    /** 后台工单管理列表（Story 4.7，全量倒序）。 */
    List<FeedbackTicket> findAllByOrderByCreatedAtDesc();

    /** 7 天自动关闭 scanner（Story 4.7）：RESOLVED 且 CSAT 死线已过（用户未评）。 */
    List<FeedbackTicket> findByStatusAndCsatDeadlineBefore(TicketStatus status, Instant before);
}
