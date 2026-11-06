package com.tailtopia.shop.order.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.pay.refund.service.RefundAuditRecorder;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.dto.RefundDestinationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * L0（V1.4.2 shop-admin Story 5.2 · AC3 / AC4 / AC5 / AC8 · O-12 / O-17）：发起人不能标记打款结果（超管不豁免、
 * 被拦截也独立留痕）；回填流水号；打款失败记原因并提醒客服；注销用户才能线下结案。
 */
class RefundSegregationTest {

    private RefundTestRig rig;
    private final RefundAuditRecorder dutyAudit = mock(RefundAuditRecorder.class);
    private final OpsAlertService ops = mock(OpsAlertService.class);

    @BeforeEach
    void setUp() {
        rig = new RefundTestRig();
        rig.service.setDutyAudit(dutyAudit);
        rig.service.setOpsAlerts(ops);
    }

    /** 发起人 7L 缺货整单取消 → 用户选 BCA → 待打款。 */
    private ShopOrderRefund pendingPayout() {
        rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.OUT_OF_STOCK, null, 7L);
        ShopOrderRefund r = rig.only();
        rig.service.chooseDestination(RefundTestRig.USER, RefundTestRig.ORDER_TOKEN, r.getPublicToken(),
                new RefundDestinationRequest("TO_BANK", "BCA", "1234567890", "Budi Santoso"));
        return r;
    }

    private static void assert403(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(AppException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(e.getMessageCode()).isEqualTo("admin.err.v142.payout.dutySeparation");
        });
    }

    @Test
    void initiatorCannotMarkPaidOrFailedAndViolationIsRecorded() {
        ShopOrderRefund r = pendingPayout();
        assert403(() -> rig.service.markPaidOut(r.getPublicToken(), "TXN-1", null, 7L));
        assert403(() -> rig.service.markPayoutFailed(r.getPublicToken(), PayoutFailReason.ACCOUNT_INVALID, null, 7L));
        verify(dutyAudit, org.mockito.Mockito.times(2)).recordShopRefundViolation(eq(7L), eq(r.getPublicToken()),
                anyString());
        assertThat(r.getStatus()).isEqualTo(ShopRefundStatus.PAYOUT_PENDING);
        assertThat(ShopOrderRefundService.isInitiator(r, 7L)).isTrue();
        assertThat(ShopOrderRefundService.isInitiator(r, 8L)).isFalse();
    }

    @Test
    void otherAdminMarksPaidWithTxnNo() {
        ShopOrderRefund r = pendingPayout();
        assertThatThrownBy(() -> rig.service.markPaidOut(r.getPublicToken(), " ", null, 8L))
                .isInstanceOf(AppException.class);
        rig.service.markPaidOut(r.getPublicToken(), " BCA-001 ", "shop-refund-proof/x/1.png", 8L);
        assertThat(r.getStatus()).isEqualTo(ShopRefundStatus.COMPLETED);
        assertThat(r.getPayoutTxnNo()).isEqualTo("BCA-001");
        assertThat(r.getPayoutProofKey()).isEqualTo("shop-refund-proof/x/1.png");
        assertThat(r.getPaidOutByAdminId()).isEqualTo(8L);
        verify(dutyAudit, never()).recordShopRefundViolation(anyLong(), anyString(), anyString());
        // 🔒 审计只记账号末 4 位
        verify(rig.audit).record(eq(8L), eq(com.tailtopia.admin.audit.service.AuditActions.SHOP_ORDER_REFUND_PAYOUT_MARKED),
                eq("SHOP_ORDER_REFUND"), eq(r.getPublicToken()), contains("账号末4位=7890"));
        verify(rig.audit, never()).record(anyLong(), anyString(), anyString(), anyString(), contains("1234567890"));
    }

    @Test
    void systemCreatedRefundIsNotBlocked() {
        rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.OUT_OF_STOCK, null, null);
        ShopOrderRefund r = rig.only();
        assertThat(ShopOrderRefundService.isInitiator(r, 7L)).isFalse();
    }

    @Test
    void payoutFailedRecordsReasonAndRemindsSupport() {
        ShopOrderRefund r = pendingPayout();
        assertThatThrownBy(() -> rig.service.markPayoutFailed(r.getPublicToken(), null, null, 8L))
                .isInstanceOf(AppException.class);
        rig.service.markPayoutFailed(r.getPublicToken(), PayoutFailReason.TRANSFER_RETURNED, "银行退回", 8L);
        assertThat(r.getStatus()).isEqualTo(ShopRefundStatus.PAYOUT_FAILED);
        assertThat(r.getPayoutFailReason()).isEqualTo(PayoutFailReason.TRANSFER_RETURNED);
        assertThat(r.getPayoutFailedByAdminId()).isEqualTo(8L);
        verify(ops).send(eq(OpsChannel.CS), startsWith("payout-failed:" + r.getPublicToken() + ":"), anyString());
    }

    @Test
    void closeOfflineOnlyForDeletedUsersAndSubjectToSegregation() {
        rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.OUT_OF_STOCK, null, 7L);
        ShopOrderRefund r = rig.only();
        assertThatThrownBy(() -> rig.service.closeOffline(r.getPublicToken(), "已线下转账", 8L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("admin.err.v142.payout.notDeletedUser"));
        rig.userDeleted();
        assertThatThrownBy(() -> rig.service.closeOffline(r.getPublicToken(), " ", 8L)).isInstanceOf(AppException.class);
        assert403(() -> rig.service.closeOffline(r.getPublicToken(), "已线下转账", 7L));
        rig.service.closeOffline(r.getPublicToken(), "已线下转账", 8L);
        assertThat(r.getStatus()).isEqualTo(ShopRefundStatus.COMPLETED);
        assertThat(r.getCashDestination()).isNull();
        assertThat(r.getOfflineClosedBy()).isEqualTo(8L);
        assertThatThrownBy(() -> rig.service.closeOffline(r.getPublicToken(), "再结一次", 8L))
                .isInstanceOf(AppException.class);
    }
}
