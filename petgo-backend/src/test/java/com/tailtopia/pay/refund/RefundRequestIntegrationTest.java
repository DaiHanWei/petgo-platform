package com.tailtopia.pay.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.refund.domain.NeedDecision;
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
 * L1（需 Docker postgres+redis）。Story 4.3 退款两段审批模型。启动即验 V71 契约（validate）。
 *
 * <p>核心：建单快照订单金额 + 唯一 order_id 防重；净额后端权威（order−fee）；payout PII 落库密文非明文 + 往返解密；
 * 职责分离三组合拒（submitter/approver/payer 两两不等，**含 SUPER_ADMIN 不豁免**——守卫为 admin_id 相等判定）；违规留审计。
 */
class RefundRequestIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private RefundService refundService;
    @Autowired
    private RefundRequestRepository refunds;
    @Autowired
    private ConsultOrderRepository orders;
    @Autowired
    private JdbcTemplate jdbc;

    private ConsultOrder seedOrder(long amount) {
        long n = SEQ.incrementAndGet();
        return orders.save(ConsultOrder.inProgress("ord-" + n, newUser().getId(), 1L, 1L,
                amount, PayChannel.QRIS, null, 30000, 60, 50000, Instant.now()));
    }

    @Test
    void create_snapshotsOrderAmount_andUniqueOrder() {
        ConsultOrder order = seedOrder(50000);
        String token = refundService.createRefundRequest(order.getOrderToken(), null, 900L);

        RefundRequest r = refunds.findByRefundToken(token).orElseThrow();
        assertThat(r.getOrderId()).isEqualTo(order.getId());
        assertThat(r.getOrderAmount()).isEqualTo(50000);
        assertThat(r.getNeedDecision()).isEqualTo(NeedDecision.PENDING);
        assertThat(r.getNetAmount()).isZero(); // 未选渠道

        // 唯一 order_id 防重复退款 → 第二次 409
        assertThatThrownBy(() -> refundService.createRefundRequest(order.getOrderToken(), null, 900L))
                .isInstanceOf(AppException.class);
    }

    @Test
    void fillPayout_netAuthoritative_andPiiEncrypted() {
        String token = refundService.createRefundRequest(seedOrder(50000).getOrderToken(), null, 900L);
        refundService.fillPayout(token, PayoutChannel.OVO, "1234567890", "Budi Santoso");

        RefundRequest r = refunds.findByRefundToken(token).orElseThrow();
        assertThat(r.getChannelFee()).isEqualTo(2500);
        assertThat(r.getNetAmount()).isEqualTo(47500); // 50000 − 2500（后端权威）
        // 实体读出解密回明文（converter 透明）
        assertThat(r.getPayoutAccount()).isEqualTo("1234567890");
        assertThat(r.getAccountHolderName()).isEqualTo("Budi Santoso");

        // DB 原始列为密文（非明文）——PII 加密红线
        String rawAcct = jdbc.queryForObject(
                "SELECT payout_account FROM refund_requests WHERE refund_token = ?", String.class, token);
        assertThat(rawAcct).isNotBlank().isNotEqualTo("1234567890");
        String rawName = jdbc.queryForObject(
                "SELECT account_holder_name FROM refund_requests WHERE refund_token = ?", String.class, token);
        assertThat(rawName).isNotEqualTo("Budi Santoso");
    }

    @Test
    void dutySeparation_blocksSameAdmin_evenSuperAdmin_andAudits() {
        String token = refundService.createRefundRequest(seedOrder(50000).getOrderToken(), null, 5L);
        refundService.submitNeed(token, 5L, NeedDecision.APPROVED); // submitter=5

        long violationsBefore = auditCount("REFUND_DUTY_VIOLATION_BLOCKED");

        // approver 不可等于 submitter（守卫只看 id，SUPER_ADMIN 亦拦）
        assertThatThrownBy(() -> refundService.approve(token, 5L)).isInstanceOf(AppException.class);
        assertThat(auditCount("REFUND_DUTY_VIOLATION_BLOCKED")).isGreaterThan(violationsBefore); // 违规留审计

        refundService.approve(token, 6L); // 不同 admin → OK，approver=6

        // payer 不可等于 submitter 或 approver
        assertThatThrownBy(() -> refundService.recordPayout(token, 5L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> refundService.recordPayout(token, 6L)).isInstanceOf(AppException.class);
        refundService.recordPayout(token, 7L); // 三方互异 → OK

        RefundRequest r = refunds.findByRefundToken(token).orElseThrow();
        assertThat(r.getSubmitterAdminId()).isEqualTo(5L);
        assertThat(r.getApproverAdminId()).isEqualTo(6L);
        assertThat(r.getPayerAdminId()).isEqualTo(7L);
    }

    private long auditCount(String action) {
        Long c = jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_logs WHERE action_type = ?", Long.class, action);
        return c == null ? 0 : c;
    }
}
