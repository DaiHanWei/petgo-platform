package com.tailtopia.pay.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.refund.domain.NeedDecision;
import com.tailtopia.pay.refund.domain.RefundRequest;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.pay.refund.service.RefundService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1（需 Docker postgres+redis）。Story 4.4 客服判定与订单解锁/回落。启动即验 V72 契约（validate）。
 *
 * <p>核心：批准→订单 {@code COMPLETED→REFUNDING} + need APPROVED + <b>无 REFUND_REJECTED 通知</b>（AB-5B）；
 * 驳回→订单 {@code COMPLETED+refund_rejected} + need REJECTED + REFUND_REJECTED 通知落库（A-2，无 PII）；
 * 重复判定 409；订单非 COMPLETED 时 CAS 幂等跳过（不误置 IN_PROGRESS——3-8 markRefunding 路径独立不受影响）。
 */
class RefundDecisionIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private RefundService refundService;
    @Autowired
    private RefundRequestRepository refunds;
    @Autowired
    private ConsultOrderRepository orders;
    @Autowired
    private JdbcTemplate jdbc;

    /** 造一个已完成（COMPLETED）订单——退款工单针对已交付订单。 */
    private ConsultOrder seedCompletedOrder(long amount) {
        long n = SEQ.incrementAndGet();
        ConsultOrder o = ConsultOrder.inProgress("ord-c-" + n, newUser().getId(), 1L, 1L,
                amount, PayChannel.QRIS, null, 30000, 60, 50000, Instant.now());
        o.markCompleted(Instant.now()); // IN_PROGRESS→COMPLETED
        return orders.save(o);
    }

    private ConsultOrder seedInProgressOrder(long amount) {
        long n = SEQ.incrementAndGet();
        return orders.save(ConsultOrder.inProgress("ord-p-" + n, newUser().getId(), 1L, 1L,
                amount, PayChannel.QRIS, null, 30000, 60, 50000, Instant.now()));
    }

    private long refundRejectedCount(long userId) {
        Long c = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = 'REFUND_REJECTED'",
                Long.class, userId);
        return c == null ? 0 : c;
    }

    @Test
    void approve_movesOrderToRefunding_needApproved_andSendsNoNotification() {
        ConsultOrder order = seedCompletedOrder(50000);
        String token = refundService.createRefundRequest(order.getOrderToken(), null, 900L);

        refundService.approveNeed(token, 901L);

        RefundRequest r = refunds.findByRefundToken(token).orElseThrow();
        assertThat(r.getNeedDecision()).isEqualTo(NeedDecision.APPROVED);
        assertThat(r.getSubmitterAdminId()).isEqualTo(901L);
        // 订单 CAS COMPLETED→REFUNDING
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(ConsultOrderStatus.REFUNDING);
        // AB-5B：批准不发任何通知
        assertThat(refundRejectedCount(order.getUserId())).isZero();
        // 审计
        assertThat(auditCount("REFUND_NEED_APPROVED")).isPositive();
    }

    @Test
    void reject_keepsOrderCompleted_setsRefundRejected_needRejected_andSendsNotification() {
        ConsultOrder order = seedCompletedOrder(50000);
        String token = refundService.createRefundRequest(order.getOrderToken(), null, 900L);

        refundService.rejectNeed(token, 902L);

        RefundRequest r = refunds.findByRefundToken(token).orElseThrow();
        assertThat(r.getNeedDecision()).isEqualTo(NeedDecision.REJECTED);
        assertThat(r.getSubmitterAdminId()).isEqualTo(902L);
        // A-2：订单保持 COMPLETED + refund_rejected=true（不假装在退款）
        ConsultOrder after = orders.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ConsultOrderStatus.COMPLETED);
        assertThat(after.isRefundRejected()).isTrue();
        // REFUND_REJECTED 通知落库给发起用户，且不含金额/账号 PII
        assertThat(refundRejectedCount(order.getUserId())).isEqualTo(1);
        String body = jdbc.queryForObject(
                "SELECT body FROM notifications WHERE recipient_user_id = ? AND type = 'REFUND_REJECTED'",
                String.class, order.getUserId());
        assertThat(body).doesNotContain("50000"); // 无金额
        // targetRef 为稳定 refundToken（非随机 token）
        String targetRef = jdbc.queryForObject(
                "SELECT target_ref FROM notifications WHERE recipient_user_id = ? AND type = 'REFUND_REJECTED'",
                String.class, order.getUserId());
        assertThat(targetRef).isEqualTo(token);
        // 审计
        assertThat(auditCount("REFUND_NEED_REJECTED")).isPositive();
    }

    @Test
    void doubleDecision_isRejectedWith409() {
        ConsultOrder order = seedCompletedOrder(50000);
        String token = refundService.createRefundRequest(order.getOrderToken(), null, 900L);
        refundService.approveNeed(token, 903L);

        // 已判定（非 PENDING）→ 重复批准/驳回均 409
        assertThatThrownBy(() -> refundService.approveNeed(token, 903L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> refundService.rejectNeed(token, 903L)).isInstanceOf(AppException.class);
    }

    @Test
    void approve_onNonCompletedOrder_isIdempotentSkip_doesNotTouchInProgress() {
        // 订单 IN_PROGRESS（3-8 markRefunding 的领域）——本 story 的 COMPLETED→REFUNDING CAS 不应命中
        ConsultOrder order = seedInProgressOrder(50000);
        String token = refundService.createRefundRequest(order.getOrderToken(), null, 900L);

        refundService.approveNeed(token, 904L); // CAS 返 0，幂等跳过不报错

        // need 仍推进为 APPROVED；但订单状态保持 IN_PROGRESS（未被 4-4 CAS 误置为 REFUNDING）
        assertThat(refunds.findByRefundToken(token).orElseThrow().getNeedDecision())
                .isEqualTo(NeedDecision.APPROVED);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(ConsultOrderStatus.IN_PROGRESS);
    }

    private long auditCount(String action) {
        Long c = jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_logs WHERE action_type = ?", Long.class, action);
        return c == null ? 0 : c;
    }
}
