package com.tailtopia.pay;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import com.tailtopia.consult.service.ConsultPayService;
import com.tailtopia.passport.service.BoardingPassService;
import com.tailtopia.passport.service.PassportSnapshotService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.service.IdCardHdService;
import com.tailtopia.profile.slot.PetSlotPurchaseService;
import com.tailtopia.purchase.domain.KeepsakeRef;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.service.KeepsakePurchaseService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.tailsonality.service.TailsonalityUnlockService;
import java.time.Duration;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * L0：V1.5.0 gift-pack Story 4-3 · AC2.5（D-18）—— {@code PACK_CREDIT} 不是支付渠道：每个付费入口第一行即 422 {@code validation}。
 *
 * <p>手法：{@code mock(X, CALLS_REAL_METHODS)} 不执行构造器，所有依赖字段为 null —— 守卫之前若碰了任何依赖（钱包 / 意图 /
 * 仓储）会 NPE 而不是 422，从而同时证明「未调钱包 / 意图」。
 */
class PayChannelPackCreditGuardTest {

    private static void assertRejected(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AppException.class, e -> {
            org.assertj.core.api.Assertions.assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            org.assertj.core.api.Assertions.assertThat(e.getType().toString()).endsWith("/validation");
        });
    }

    private static <T> T real(Class<T> type) {
        return mock(type, CALLS_REAL_METHODS);
    }

    @Test
    void createIntentRejectsPackCredit() {
        PaymentIntentService s = real(PaymentIntentService.class);
        assertRejected(() -> s.createIntent(1L, PaymentPurpose.GIFT, PayChannel.PACK_CREDIT, 1000, "IDR", "k",
                Duration.ofMinutes(60)));
        assertRejected(() -> s.createIntent(1L, PaymentPurpose.GIFT, PayChannel.PACK_CREDIT, 1000, "IDR", "k"));
    }

    @Test
    void paidUnlockEntrancesRejectPackCredit() {
        IdCardHdService hd = real(IdCardHdService.class);
        assertRejected(() -> hd.purchaseCard(1L, 2L, PayChannel.PACK_CREDIT));
        assertRejected(() -> hd.purchase(1L, 2L, PayChannel.PACK_CREDIT));

        KeepsakePurchaseService keepsake = real(KeepsakePurchaseService.class);
        assertRejected(() -> keepsake.start(1L,
                new KeepsakeRef(KeepsakeSku.TAILSONALITY, 3L, "tok", 4L, false, null), PayChannel.PACK_CREDIT));

        TailsonalityUnlockService ts = real(TailsonalityUnlockService.class);
        assertRejected(() -> ts.unlock(1L, "tok", PayChannel.PACK_CREDIT));
        assertRejected(() -> ts.unlockMatch(1L, "tok", PayChannel.PACK_CREDIT));

        BoardingPassService bp = real(BoardingPassService.class);
        assertRejected(() -> bp.unlock(1L, 2L, "place", PayChannel.PACK_CREDIT));

        PassportSnapshotService snap = real(PassportSnapshotService.class);
        assertRejected(() -> snap.start(1L, 2L, PayChannel.PACK_CREDIT));

        ConsultPayService consult = real(ConsultPayService.class);
        assertRejected(() -> consult.pay(1L, "req", PayChannel.PACK_CREDIT));
    }

    @Test
    void petSlotRentAndRenewRejectPackCredit() {
        PetSlotPurchaseService slots = real(PetSlotPurchaseService.class);
        assertRejected(() -> slots.rent(1L, 1, PayChannel.PACK_CREDIT, "client-key"));
        assertRejected(() -> slots.renew(1L, List.of("r1"), PayChannel.PACK_CREDIT, "client-key"));
    }
}
