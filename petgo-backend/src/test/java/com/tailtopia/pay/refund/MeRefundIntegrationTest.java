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
import com.tailtopia.pay.refund.dto.MyRefundView;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.pay.refund.service.RefundService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1（需 Docker postgres+redis）。Story 4.5 用户端选退款方式与填收款。
 *
 * <p>核心：列表仅本人 + 零 PII；PawCoin 即时退→钱包 +amount + 订单 REFUNDED + approval DONE + 幂等；
 * QRIS 填账户→净额后端权威（order−fee）+ PENDING_APPROVAL + PII 密文 + 不可逆重复提交 409；owner 403；渠道/状态门控 409。
 */
class MeRefundIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private RefundService refundService;
    @Autowired
    private RefundRequestRepository refunds;
    @Autowired
    private ConsultOrderRepository orders;
    @Autowired
    private JdbcTemplate jdbc;

    /** 造 COMPLETED 订单（指定用户 + 支付渠道）。 */
    private ConsultOrder seedCompletedOrder(long userId, long amount, PayChannel channel) {
        long n = SEQ.incrementAndGet();
        ConsultOrder o = ConsultOrder.inProgress("ord-45-" + n, userId, 1L, 1L,
                amount, channel, null, 30000, 60, 50000, Instant.now());
        o.markCompleted(Instant.now());
        return orders.save(o);
    }

    /** 建退款请求 + 客服批准（4-4）→ 订单进 REFUNDING、need=APPROVED。返回 refundToken。 */
    private String approvedRefund(ConsultOrder order) {
        String token = refundService.createRefundRequest(order.getOrderToken(), null, 900L);
        refundService.approveNeed(token, 901L);
        return token;
    }

    private long walletBalance(long userId) {
        List<Long> b = jdbc.queryForList(
                "SELECT balance FROM pawcoin_wallets WHERE user_id = ?", Long.class, userId);
        return b.isEmpty() ? 0 : b.get(0); // 钱包首次退款前无行 → 0
    }

    @Test
    void listMyRefunds_onlyOwn_actionableWhenApproved_noPii() {
        long userA = newUser().getId();
        long userB = newUser().getId();
        ConsultOrder order = seedCompletedOrder(userA, 50000, PayChannel.QRIS);
        String token = approvedRefund(order);

        List<MyRefundView> aList = refundService.listMyRefunds(userA);
        assertThat(aList).hasSize(1);
        MyRefundView v = aList.get(0);
        assertThat(v.refundToken()).isEqualTo(token);
        assertThat(v.payChannel()).isEqualTo("QRIS");
        assertThat(v.refundMethod()).isEqualTo("REAL_MONEY");
        assertThat(v.needDecision()).isEqualTo("APPROVED");
        assertThat(v.approvalStatus()).isNull();
        assertThat(v.actionable()).isTrue();
        assertThat(v.payoutFilled()).isFalse();
        // QRIS 出款渠道费预览：net = order − fee（后端权威）
        assertThat(v.payoutOptions()).extracting(MyRefundView.PayoutOption::channel)
                .containsExactlyInAnyOrder("BCA", "OVO", "GOPAY");
        MyRefundView.PayoutOption ovo = v.payoutOptions().stream()
                .filter(o -> o.channel().equals("OVO")).findFirst().orElseThrow();
        assertThat(ovo.fee()).isEqualTo(2500);
        assertThat(ovo.net()).isEqualTo(47500);
        // 转 PawCoin 预览：默认 premium=0 → = 订单额
        assertThat(v.pawcoinCreditPreview()).isEqualTo(50000);

        // userB 看不到 A 的退款
        assertThat(refundService.listMyRefunds(userB)).isEmpty();
    }

    @Test
    void pawcoinOrder_instantRefund_creditsWallet_orderRefunded_done_idempotent() {
        long userId = newUser().getId();
        ConsultOrder order = seedCompletedOrder(userId, 50000, PayChannel.PAWCOIN);
        String token = approvedRefund(order);
        long before = walletBalance(userId);

        refundService.refundToPawCoin(token, userId);

        assertThat(walletBalance(userId)).isEqualTo(before + 50000); // 全额、无手续费
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(ConsultOrderStatus.REFUNDED);
        assertThat(refunds.findByRefundToken(token).orElseThrow().getApprovalStatus())
                .isEqualTo(ApprovalStatus.DONE);

        // 幂等：重复确认不重复入账
        refundService.refundToPawCoin(token, userId);
        assertThat(walletBalance(userId)).isEqualTo(before + 50000);
    }

    @Test
    void qrisOrder_fillPayout_netAuthoritative_pending_piiEncrypted_irreversible() {
        long userId = newUser().getId();
        ConsultOrder order = seedCompletedOrder(userId, 50000, PayChannel.QRIS);
        String token = approvedRefund(order);

        refundService.fillPayoutByUser(token, userId, PayoutChannel.OVO, "1234567890", "Budi Santoso");

        RefundRequest r = refunds.findByRefundToken(token).orElseThrow();
        assertThat(r.getNetAmount()).isEqualTo(47500); // 50000 − 2500（后端权威）
        assertThat(r.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);
        // 订单留 REFUNDING（等 4-6 打款），本 story 不置 REFUNDED
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(ConsultOrderStatus.REFUNDING);
        // DB 原始 PII 列为密文
        String rawAcct = jdbc.queryForObject(
                "SELECT payout_account FROM refund_requests WHERE refund_token = ?", String.class, token);
        assertThat(rawAcct).isNotBlank().isNotEqualTo("1234567890");

        // 不可逆：已提交再次提交 → 409
        assertThatThrownBy(() -> refundService.fillPayoutByUser(token, userId,
                PayoutChannel.BCA, "999", "X")).isInstanceOf(AppException.class);
    }

    @Test
    void ownerGating_otherUser_forbidden() {
        long owner = newUser().getId();
        long attacker = newUser().getId();
        ConsultOrder pawOrder = seedCompletedOrder(owner, 50000, PayChannel.PAWCOIN);
        String pawToken = approvedRefund(pawOrder);
        ConsultOrder qrisOrder = seedCompletedOrder(owner, 50000, PayChannel.QRIS);
        String qrisToken = approvedRefund(qrisOrder);

        assertThatThrownBy(() -> refundService.refundToPawCoin(pawToken, attacker))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> refundService.fillPayoutByUser(qrisToken, attacker,
                PayoutChannel.BCA, "1", "X")).isInstanceOf(AppException.class);
    }

    @Test
    void pawcoinOrder_fillRealMoney_rejected() {
        // PawCoin 订单走填真钱账户 → 409（原路退币，无需真钱账户）
        long userId = newUser().getId();
        ConsultOrder paw = seedCompletedOrder(userId, 50000, PayChannel.PAWCOIN);
        String pawToken = approvedRefund(paw);
        assertThatThrownBy(() -> refundService.fillPayoutByUser(pawToken, userId,
                PayoutChannel.BCA, "1", "X")).isInstanceOf(AppException.class);
    }

    @Test
    void qrisOrder_convertToPawCoin_creditsBasePlusBonus_returnsResult_idempotent() {
        long userId = newUser().getId();
        ConsultOrder order = seedCompletedOrder(userId, 50000, PayChannel.QRIS);
        String token = approvedRefund(order);
        long before = walletBalance(userId);
        // 配置 bonus：10% + 固定 2000 → bonus = 50000×10/100 + 2000 = 7000；到账 57000
        jdbc.update("UPDATE pawcoin_config SET premium_rate = 10, premium_fixed = 2000 WHERE id = 1");
        try {
            var result = refundService.refundToPawCoin(token, userId);

            assertThat(result.baseAmount()).isEqualTo(50000);
            assertThat(result.bonusAmount()).isEqualTo(7000);
            assertThat(result.totalCredited()).isEqualTo(57000);
            assertThat(result.newBalance()).isEqualTo(before + 57000);
            assertThat(walletBalance(userId)).isEqualTo(before + 57000);
            assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                    .isEqualTo(ConsultOrderStatus.REFUNDED);
            assertThat(refunds.findByRefundToken(token).orElseThrow().getApprovalStatus())
                    .isEqualTo(ApprovalStatus.DONE);

            // 幂等：重复确认不重复入账
            var again = refundService.refundToPawCoin(token, userId);
            assertThat(walletBalance(userId)).isEqualTo(before + 57000);
            assertThat(again.totalCredited()).isEqualTo(57000);
        } finally {
            jdbc.update("UPDATE pawcoin_config SET premium_rate = 0, premium_fixed = 0 WHERE id = 1");
        }
    }

    @Test
    void qrisOrder_afterFillPayout_cannotConvertToPawCoin() {
        // 真钱路径已启动（PENDING_APPROVAL）→ 再转币被拒（防双退）
        long userId = newUser().getId();
        ConsultOrder order = seedCompletedOrder(userId, 50000, PayChannel.QRIS);
        String token = approvedRefund(order);
        refundService.fillPayoutByUser(token, userId, PayoutChannel.BCA, "123", "X");
        assertThatThrownBy(() -> refundService.refundToPawCoin(token, userId))
                .isInstanceOf(AppException.class);
    }

    @Test
    void notApproved_rejected() {
        long userId = newUser().getId();
        ConsultOrder order = seedCompletedOrder(userId, 50000, PayChannel.PAWCOIN);
        // 仅建请求、未经客服批准（need=PENDING）
        String token = refundService.createRefundRequest(order.getOrderToken(), null, 900L);
        assertThatThrownBy(() -> refundService.refundToPawCoin(token, userId))
                .isInstanceOf(AppException.class);
    }
}
