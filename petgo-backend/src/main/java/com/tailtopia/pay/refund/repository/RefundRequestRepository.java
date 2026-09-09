package com.tailtopia.pay.refund.repository;

import com.tailtopia.pay.refund.domain.RefundRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 退款请求读写（Story 4.3）。一订单一请求（唯一 order_id，DB 约束 + service 预检）。
 */
public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {

    Optional<RefundRequest> findByRefundToken(String refundToken);

    boolean existsByOrderId(long orderId);

    /** 订单详情退款子阶段派生（Story 5.3，一订单一退款）。 */
    Optional<RefundRequest> findByOrderId(long orderId);

    /** 用户端「我的退款」列表（Story 4.5，仅本人，倒序）。 */
    List<RefundRequest> findByUserIdOrderByCreatedAtDesc(long userId);

    /** 待办中心角标（V1.3.0 Story 2.2）：待审批 + 已审批待打款。 */
    long countByApprovalStatusIn(java.util.Collection<com.tailtopia.pay.refund.domain.ApprovalStatus> statuses);

    // ===== V1.3.0 Story 2.8：A6 三段流页签（只读查询；先进先出 = createdAt 升序） =====

    /** 待客服判定：need_decision = PENDING。 */
    org.springframework.data.domain.Page<RefundRequest> findByNeedDecisionOrderByCreatedAtAsc(
            com.tailtopia.pay.refund.domain.NeedDecision needDecision, org.springframework.data.domain.Pageable pageable);

    long countByNeedDecision(com.tailtopia.pay.refund.domain.NeedDecision needDecision);

    /** 待主管审批：客服已批 且（用户尚未填收款 approval_status 为空 / 已填 PENDING_APPROVAL）。 */
    @org.springframework.data.jpa.repository.Query("select r from RefundRequest r where r.needDecision = com.tailtopia.pay.refund.domain.NeedDecision.APPROVED"
            + " and (r.approvalStatus is null or r.approvalStatus = com.tailtopia.pay.refund.domain.ApprovalStatus.PENDING_APPROVAL) order by r.createdAt asc")
    org.springframework.data.domain.Page<RefundRequest> findApprovalStage(org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("select count(r) from RefundRequest r where r.needDecision = com.tailtopia.pay.refund.domain.NeedDecision.APPROVED"
            + " and (r.approvalStatus is null or r.approvalStatus = com.tailtopia.pay.refund.domain.ApprovalStatus.PENDING_APPROVAL)")
    long countApprovalStage();

    /** 待财务打款：APPROVED / PROCESSING。 */
    org.springframework.data.domain.Page<RefundRequest> findByApprovalStatusInOrderByCreatedAtAsc(
            java.util.Collection<com.tailtopia.pay.refund.domain.ApprovalStatus> statuses, org.springframework.data.domain.Pageable pageable);

    /** 已完结 · 已驳回：客服驳回 / 主管驳回 / 已打款（最新在前）。 */
    @org.springframework.data.jpa.repository.Query("select r from RefundRequest r where r.needDecision = com.tailtopia.pay.refund.domain.NeedDecision.REJECTED"
            + " or r.approvalStatus in (com.tailtopia.pay.refund.domain.ApprovalStatus.REJECTED, com.tailtopia.pay.refund.domain.ApprovalStatus.DONE) order by r.createdAt desc")
    org.springframework.data.domain.Page<RefundRequest> findClosedStage(org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("select count(r) from RefundRequest r where r.needDecision = com.tailtopia.pay.refund.domain.NeedDecision.REJECTED"
            + " or r.approvalStatus in (com.tailtopia.pay.refund.domain.ApprovalStatus.REJECTED, com.tailtopia.pay.refund.domain.ApprovalStatus.DONE)")
    long countClosedStage();
}
