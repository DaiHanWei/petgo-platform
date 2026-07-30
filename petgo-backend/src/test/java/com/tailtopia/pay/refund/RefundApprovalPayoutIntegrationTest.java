package com.tailtopia.pay.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.refund.domain.ApprovalStatus;
import com.tailtopia.pay.refund.domain.PayoutChannel;
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
 * L1（需 Docker postgres+redis，桩网关）。Story 4.6 退款第二段审批闭环（主管审批 + 财务 Iris 打款）。
 *
 * <p>核心：主管通过→APPROVED+note；驳回→REJECTED+订单回落 COMPLETED+refund_rejected+REFUND_REJECTED 通知；
 * 财务打款→DONE+payment_proof+订单 REFUNDED+REFUND_OUT 借贷平；职责分离三组合拒（SUPER_ADMIN 不豁免）；
 * 幂等（重复打款不双出/不双记）；兽医聚合排除 REFUNDED。
 */
class RefundApprovalPayoutIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private RefundService refundService;
    @Autowired
    private RefundRequestRepository refunds;
    @Autowired
    private ConsultOrderRepository orders;
    @Autowired
    private JdbcTemplate jdbc;

    private ConsultOrder seedCompletedQrisOrder(long userId, long vetId, long amount) {
        long n = SEQ.incrementAndGet();
        ConsultOrder o = ConsultOrder.inProgress("ord-46-" + n, userId, vetId, 1L,
                amount, PayChannel.QRIS, null, 30000, 60, 50000, Instant.now());
        o.markCompleted(Instant.now());
        return orders.save(o);
    }

    /** 建退款 + 客服批准(need，submitter) + 用户填收款(QRIS OVO) → approval PENDING_APPROVAL。返回 token。 */
    private String pendingApprovalRefund(ConsultOrder order, long submitterAdminId) {
        String token = refundService.createRefundRequest(order.getOrderToken(), null, submitterAdminId);
        refundService.approveNeed(token, submitterAdminId); // 订单 REFUNDING、need APPROVED、submitter 记
        long userId = order.getUserId();
        refundService.fillPayoutByUser(token, userId, PayoutChannel.OVO, "1234567890", "Budi");
        return token;
    }

    /**
     * 按 token 域幂等键计数（`refund-out-{token}` 永不复用，隔离共享 scratch 库跨运行 id 复用污染——
     * consult 测试 CASCADE 删 refund_requests + IDENTITY 复用会留旧 ledger 孤儿行）。
     */
    private long ledgerCount(String token, String direction, String account) {
        Long c = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE idempotency_key=? AND direction=? AND account=?",
                Long.class, "refund-out-" + token, direction, account);
        return c == null ? 0 : c;
    }

    @Test
    void approve_thenPayout_orderRefunded_proof_ledgerBalanced() {
        long userId = newUser().getId();
        ConsultOrder order = seedCompletedQrisOrder(userId, 810000L + SEQ.incrementAndGet(), 50000);
        String token = pendingApprovalRefund(order, 800L); // submitter=800

        refundService.approveRefund(token, 801L, "凭证已核"); // approver=801≠submitter

        RefundRequest r = refunds.findByRefundToken(token).orElseThrow();
        assertThat(r.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(r.getApprovalNote()).isEqualTo("凭证已核");
        assertThat(r.getApprovedAt()).isNotNull();
        // 订单仍 REFUNDING（等财务打款）
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(ConsultOrderStatus.REFUNDING);

        refundService.payoutRefund(token, 802L); // payer=802≠submitter≠approver

        r = refunds.findByRefundToken(token).orElseThrow();
        assertThat(r.getApprovalStatus()).isEqualTo(ApprovalStatus.DONE);
        assertThat(r.getPaymentProof()).isEqualTo("stub-payout-" + token); // 桩出款凭证
        assertThat(r.getPaidAt()).isNotNull();
        // 订单终态 REFUNDED
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(ConsultOrderStatus.REFUNDED);
        // REFUND_OUT 双分录借贷平（net=50000−2500=47500）
        assertThat(ledgerCount(token, "DEBIT", "REFUND_OUT")).isEqualTo(1);
        assertThat(ledgerCount(token, "CREDIT", "CASH_IN")).isEqualTo(1);
        Long debit = jdbc.queryForObject(
                "SELECT amount FROM ledger_entries WHERE idempotency_key=? AND account='REFUND_OUT'",
                Long.class, "refund-out-" + token);
        assertThat(debit).isEqualTo(47500);
    }

    @Test
    void payout_idempotent_noDoublePayoutOrLedger() {
        long userId = newUser().getId();
        ConsultOrder order = seedCompletedQrisOrder(userId, 820000L + SEQ.incrementAndGet(), 50000);
        String token = pendingApprovalRefund(order, 810L);
        refundService.approveRefund(token, 811L, "ok");
        refundService.payoutRefund(token, 812L);

        // 重复打款 → 幂等短路（DONE），不重复记账
        refundService.payoutRefund(token, 812L);

        assertThat(ledgerCount(token, "DEBIT", "REFUND_OUT")).isEqualTo(1); // 仍 1 条
    }

    @Test
    void reject_orderRevertsCompleted_refundRejected_andNotifies() {
        long userId = newUser().getId();
        ConsultOrder order = seedCompletedQrisOrder(userId, 830000L + SEQ.incrementAndGet(), 50000);
        String token = pendingApprovalRefund(order, 820L);

        refundService.rejectRefund(token, 821L, "凭证不符");

        RefundRequest r = refunds.findByRefundToken(token).orElseThrow();
        assertThat(r.getApprovalStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(r.getRejectReason()).isEqualTo("凭证不符");
        // 订单回落 COMPLETED + refund_rejected
        ConsultOrder after = orders.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ConsultOrderStatus.COMPLETED);
        assertThat(after.isRefundRejected()).isTrue();
        // REFUND_REJECTED 通知落库
        Long notif = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_user_id=? AND type='REFUND_REJECTED'",
                Long.class, userId);
        assertThat(notif).isEqualTo(1);
    }

    @Test
    void dutySeparation_blocksSameAdmin_evenSuperAdmin() {
        long userId = newUser().getId();
        ConsultOrder order = seedCompletedQrisOrder(userId, 840000L + SEQ.incrementAndGet(), 50000);
        String token = pendingApprovalRefund(order, 900L); // submitter=900

        // 主管≠提交人
        assertThatThrownBy(() -> refundService.approveRefund(token, 900L, "x"))
                .isInstanceOf(AppException.class);
        refundService.approveRefund(token, 901L, "ok"); // approver=901

        // 财务≠提交人 且 ≠审批人
        assertThatThrownBy(() -> refundService.payoutRefund(token, 900L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> refundService.payoutRefund(token, 901L)).isInstanceOf(AppException.class);
        refundService.payoutRefund(token, 902L); // payer=902 三方互异 → OK

        assertThat(refunds.findByRefundToken(token).orElseThrow().getApprovalStatus())
                .isEqualTo(ApprovalStatus.DONE);
    }

    @Test
    void vetAggregation_excludesRefundedOrder() {
        long userId = newUser().getId();
        long vetId = 850000L + SEQ.incrementAndGet(); // 独占兽医，隔离聚合
        ConsultOrder order = seedCompletedQrisOrder(userId, vetId, 50000);
        Instant from = Instant.now().minusSeconds(3600);
        Instant to = Instant.now().plusSeconds(3600);
        // 完成态：聚合含此单
        assertThat(orders.aggregateCompletedForVet(vetId, from, to)).isPresent();

        String token = pendingApprovalRefund(order, 860L);
        refundService.approveRefund(token, 861L, "ok");
        refundService.payoutRefund(token, 862L); // 订单 REFUNDED

        // REFUNDED 后聚合自动排除→月结随动（兽医不再得该单分成）
        assertThat(orders.aggregateCompletedForVet(vetId, from, to)).isEmpty();
    }

    @Test
    void approve_requiresPendingApproval_andNote() {
        long userId = newUser().getId();
        ConsultOrder order = seedCompletedQrisOrder(userId, 870000L + SEQ.incrementAndGet(), 50000);
        // 未填收款（approval_status 空）→ 主管审批 409
        String token = refundService.createRefundRequest(order.getOrderToken(), null, 870L);
        refundService.approveNeed(token, 870L);
        assertThatThrownBy(() -> refundService.approveRefund(token, 871L, "ok"))
                .isInstanceOf(AppException.class);
    }
}
