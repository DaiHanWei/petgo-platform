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

    /** 待办中心角标（V1.3.0 Story 2.2）：OPEN + IN_PROGRESS 工单数。 */
    long countByStatusIn(java.util.Collection<TicketStatus> statuses);

    // ===== V1.3.0 Story 2.7：A5 工作台三态（待处理 / 待联系 / 已结案），时间倒序分页 =====

    org.springframework.data.domain.Page<FeedbackTicket> findByStatusInOrderByCreatedAtDesc(
            java.util.Collection<TicketStatus> statuses, org.springframework.data.domain.Pageable pageable);

    /** 待联系 = 未结案 且 需联系 且 未联系。 */
    org.springframework.data.domain.Page<FeedbackTicket>
            findByStatusInAndNeedContactCustomerTrueAndContactedCustomerFalseOrderByCreatedAtDesc(
            java.util.Collection<TicketStatus> statuses, org.springframework.data.domain.Pageable pageable);

    long countByStatusInAndNeedContactCustomerTrueAndContactedCustomerFalse(java.util.Collection<TicketStatus> statuses);

    /** 处置后「下一条」：最新一条未结案工单。 */
    Optional<FeedbackTicket> findFirstByStatusInOrderByCreatedAtDesc(java.util.Collection<TicketStatus> statuses);

    /** 待联系页签的「下一条」。 */
    Optional<FeedbackTicket> findFirstByStatusInAndNeedContactCustomerTrueAndContactedCustomerFalseOrderByCreatedAtDesc(
            java.util.Collection<TicketStatus> statuses);

    /** 7 天自动关闭 scanner（Story 4.7）：RESOLVED 且 CSAT 死线已过（用户未评）。 */
    List<FeedbackTicket> findByStatusAndCsatDeadlineBefore(TicketStatus status, Instant before);
}
