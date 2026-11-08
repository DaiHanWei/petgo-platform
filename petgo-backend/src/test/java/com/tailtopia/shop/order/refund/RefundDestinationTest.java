package com.tailtopia.shop.order.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.refund.domain.PayoutChannel;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.dto.RefundDestinationRequest;
import com.tailtopia.shop.order.dto.ShopOrderRefundView;
import com.tailtopia.shop.returns.domain.CashDestination;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** L0：v1.4.2 shop Story 4.1 AC4 / AC5 —— 用户选择现金去向与财务标记（Mockito）。 */
class RefundDestinationTest {

    private final RefundTestRig rig = new RefundTestRig();

    private static final RefundDestinationRequest PAWCOIN = new RefundDestinationRequest("TO_PAWCOIN", null, null,
            null);

    private static RefundDestinationRequest bank(String channel) {
        return new RefundDestinationRequest("TO_BANK", channel, " 1234567890 ", "Budi Santoso");
    }

    private ShopOrderRefund wholeCancelled(CancelReason reason) {
        if (reason == CancelReason.USER_FAULT_RETURNED) {
            rig.userFaultReceived();   // V1.4.2 shop-admin 5.1：先按行入库、后取消（取消时即出退款单）
            rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, reason, null, 7L);
        } else {
            rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, reason, null, 7L);
        }
        clearInvocations(rig.wallet, rig.events, rig.notifications);
        return rig.only();
    }

    private ShopOrderRefundView choose(ShopOrderRefund r, RefundDestinationRequest req) {
        return rig.service.chooseDestination(RefundTestRig.USER, RefundTestRig.ORDER_TOKEN, r.getPublicToken(), req);
    }

    @Test
    @DisplayName("平台原因选 PawCoin：现金段入账 + 补偿（只按现金段 229,575 × 10% = 22,957）→ COMPLETED，订单 REFUNDED")
    void toPawcoinWithCompensation() {
        ShopOrderRefund r = wholeCancelled(CancelReason.OUT_OF_STOCK);
        ShopOrderRefundView v = choose(r, PAWCOIN);

        verify(rig.wallet).credit(RefundTestRig.USER, 229_575L, PawCoinTxnType.REFUND, "SHOP_ORDER",
                RefundTestRig.ORDER_ID, "shopref:" + r.getPublicToken() + ":cash-pawcoin");
        verify(rig.wallet).credit(RefundTestRig.USER, 22_957L, PawCoinTxnType.BONUS, "SHOP_ORDER_COMPENSATION",
                RefundTestRig.ORDER_ID, "shopref:" + r.getPublicToken() + ":compensation");
        assertThat(r.getStatus()).isEqualTo(ShopRefundStatus.COMPLETED);
        assertThat(r.getCashDestination()).isEqualTo(CashDestination.TO_PAWCOIN);
        assertThat(r.getCompensationAmount()).isEqualTo(22_957L);
        assertThat(v.compensationAmount()).isEqualTo(22_957L);
        assertThat(v.canChooseDestination()).isFalse();
        assertThat(rig.order.getStatus()).isEqualTo(ShopOrderStatus.REFUNDED);
        verifyNoInteractions(rig.notifications);   // 选择退款方式不推送
    }

    @Test
    @DisplayName("补偿封顶：cap 10,000")
    void compensationCap() {
        rig.compensation(10, 10_000L);
        ShopOrderRefund r = wholeCancelled(CancelReason.OUT_OF_STOCK);
        choose(r, PAWCOIN);
        assertThat(r.getCompensationAmount()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("用户原因退回选 PawCoin：无补偿")
    void userFaultNoCompensation() {
        ShopOrderRefund r = wholeCancelled(CancelReason.USER_FAULT_RETURNED);
        choose(r, PAWCOIN);
        verify(rig.wallet).credit(eq(RefundTestRig.USER), eq(r.getCashRefund()), eq(PawCoinTxnType.REFUND),
                eq("SHOP_ORDER"), eq(RefundTestRig.ORDER_ID), anyString());
        verify(rig.wallet, never()).credit(anyLong(), anyLong(), eq(PawCoinTxnType.BONUS), anyString(), anyLong(),
                anyString());
        assertThat(r.getCompensationAmount()).isZero();
    }

    @Test
    @DisplayName("TO_BANK（OVO）：渠道费后端算 2,500、账户与户名写入加密字段 → PAYOUT_PENDING；不入账；出参只给末 4 位")
    void toBank() {
        ShopOrderRefund r = wholeCancelled(CancelReason.OUT_OF_STOCK);
        ShopOrderRefundView v = choose(r, bank("ovo"));

        assertThat(r.getStatus()).isEqualTo(ShopRefundStatus.PAYOUT_PENDING);
        assertThat(r.getPayoutChannel()).isEqualTo(PayoutChannel.OVO);
        assertThat(r.getPayoutFee()).isEqualTo(2_500L);
        assertThat(r.getPayoutAccount()).isEqualTo("1234567890");
        assertThat(r.getPayoutAccountHolder()).isEqualTo("Budi Santoso");
        assertThat(r.getDestinationChosenAt()).isEqualTo(RefundTestRig.NOW);
        verifyNoInteractions(rig.wallet);
        assertThat(v.payoutAccountLast4()).isEqualTo("7890");
        assertThat(v.toString()).doesNotContain("1234567890").doesNotContain("Budi");
        assertThat(rig.order.getStatus()).isEqualTo(ShopOrderStatus.REFUNDING);
    }

    @Test
    @DisplayName("TO_BANK：现金段 ≤ 渠道费 → 422 refund-below-payout-fee；缺渠道 / 账号 / 户名 → 422")
    void toBankValidation() {
        rig.o1(true, 329_575L - 2_000L);
        ShopOrderRefund r = wholeCancelled(CancelReason.OUT_OF_STOCK);
        assertThat(r.getCashRefund()).isEqualTo(2_000L);
        assertThatThrownBy(() -> choose(r, bank("OVO"))).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getType()).isEqualTo(ErrorTypes.REFUND_BELOW_PAYOUT_FEE));
        assertThat(r.getStatus()).isEqualTo(ShopRefundStatus.AWAITING_DESTINATION);

        for (RefundDestinationRequest bad : new RefundDestinationRequest[] {
                new RefundDestinationRequest("TO_BANK", null, "123", "Budi"),
                new RefundDestinationRequest("TO_BANK", "BCA", " ", "Budi"),
                new RefundDestinationRequest("TO_BANK", "BCA", "123", null),
                new RefundDestinationRequest("TO_BANK", "DANA", "123", "Budi"),
                new RefundDestinationRequest(null, null, null, null)}) {
            assertThatThrownBy(() -> choose(r, bad)).isInstanceOfSatisfying(AppException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }
        // BCA 无手续费，2,000 可以退到银行
        choose(r, bank("BCA"));
        assertThat(r.getStatus()).isEqualTo(ShopRefundStatus.PAYOUT_PENDING);
        assertThat(r.getPayoutFee()).isZero();
    }

    @Test
    @DisplayName("非可选状态 → 409 refund-destination-locked（已选 PawCoin / 打款中）")
    void lockedStatus() {
        ShopOrderRefund r = wholeCancelled(CancelReason.OUT_OF_STOCK);
        choose(r, bank("BCA"));
        assertThatThrownBy(() -> choose(r, PAWCOIN)).isInstanceOfSatisfying(AppException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getType()).isEqualTo(ErrorTypes.REFUND_DESTINATION_LOCKED);
        });
    }

    @Test
    @DisplayName("PAYOUT_FAILED 可重选：改选 PawCoin 照给补偿；或重填银行")
    void reChooseAfterPayoutFailed() {
        ShopOrderRefund r = wholeCancelled(CancelReason.OUT_OF_STOCK);
        choose(r, bank("GOPAY"));
        rig.service.markPayoutFailed(r.getPublicToken(), "账号不存在", 8L);
        assertThat(r.getStatus()).isEqualTo(ShopRefundStatus.PAYOUT_FAILED);
        assertThat(r.getPayoutFailNote()).isEqualTo("账号不存在");
        verifyNoInteractions(rig.notifications);   // 打款失败不推送

        choose(r, PAWCOIN);
        assertThat(r.getStatus()).isEqualTo(ShopRefundStatus.COMPLETED);
        assertThat(r.getCompensationAmount()).isEqualTo(22_957L);
        assertThat(r.getPayoutAccount()).isNull();

        RefundTestRig other = new RefundTestRig();
        other.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.OUT_OF_STOCK, null, 7L);
        ShopOrderRefund r2 = other.only();
        other.service.chooseDestination(RefundTestRig.USER, RefundTestRig.ORDER_TOKEN, r2.getPublicToken(),
                bank("GOPAY"));
        other.service.markPayoutFailed(r2.getPublicToken(), null, 8L);
        other.service.chooseDestination(RefundTestRig.USER, RefundTestRig.ORDER_TOKEN, r2.getPublicToken(),
                bank("BCA"));
        assertThat(r2.getStatus()).isEqualTo(ShopRefundStatus.PAYOUT_PENDING);
        assertThat(r2.getPayoutChannel()).isEqualTo(PayoutChannel.BCA);
    }

    @Test
    @DisplayName("markPaidOut：PAYOUT_PENDING → COMPLETED、写 paid_out_at、订单 REFUNDED；重复调用幂等；非待打款 409")
    void markPaidOutIdempotent() {
        ShopOrderRefund r = wholeCancelled(CancelReason.OUT_OF_STOCK);
        assertThatThrownBy(() -> rig.service.markPaidOut(r.getPublicToken(), 8L))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        choose(r, bank("BCA"));
        ShopOrderRefundView v = rig.service.markPaidOut(r.getPublicToken(), 8L);
        assertThat(r.getStatus()).isEqualTo(ShopRefundStatus.COMPLETED);
        assertThat(v.paidOutAt()).isEqualTo(RefundTestRig.NOW);
        assertThat(rig.order.getStatus()).isEqualTo(ShopOrderStatus.REFUNDED);

        rig.service.markPaidOut(r.getPublicToken(), 8L);
        verify(rig.audit).record(eq(8L), eq("SHOP_ORDER_REFUND_PAYOUT_MARKED"), anyString(), eq(r.getPublicToken()),
                anyString());
    }

    @Test
    @DisplayName("markPayoutFailed：备注超 200 字 422；只允许 PAYOUT_PENDING")
    void markPayoutFailedGuards() {
        ShopOrderRefund r = wholeCancelled(CancelReason.OUT_OF_STOCK);
        assertThatThrownBy(() -> rig.service.markPayoutFailed(r.getPublicToken(), null, 8L))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        choose(r, bank("BCA"));
        assertThatThrownBy(() -> rig.service.markPayoutFailed(r.getPublicToken(), "x".repeat(201), 8L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    @DisplayName("订单非本人 / 退款单不属于该订单 → 404（不区分）")
    void notFound() {
        ShopOrderRefund r = wholeCancelled(CancelReason.OUT_OF_STOCK);
        assertThatThrownBy(() -> rig.service.chooseDestination(999L, RefundTestRig.ORDER_TOKEN, r.getPublicToken(),
                PAWCOIN)).isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> rig.service.chooseDestination(RefundTestRig.USER, RefundTestRig.ORDER_TOKEN,
                "other-refund", PAWCOIN)).isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(rig.wallet, never()).credit(anyLong(), anyLong(), any(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("详情 refunds[]：待选时给 compensationIfPawcoin 预估；完成后为 0")
    void refundViews() {
        ShopOrderRefund r = wholeCancelled(CancelReason.OUT_OF_STOCK);
        assertThat(rig.service.refundViewsOf(rig.order)).singleElement().satisfies(v -> {
            assertThat(v.refundToken()).isEqualTo(r.getPublicToken());
            assertThat(v.canChooseDestination()).isTrue();
            assertThat(v.compensate()).isTrue();
            assertThat(v.compensationIfPawcoin()).isEqualTo(22_957L);
            assertThat(v.payoutAccountLast4()).isNull();
        });
        choose(r, PAWCOIN);
        assertThat(rig.service.refundViewsOf(rig.order).get(0).compensationIfPawcoin()).isZero();
    }
}
