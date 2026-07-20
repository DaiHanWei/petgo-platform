package com.tailtopia.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.PaymentGateway;
import java.util.Map;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.triage.TriageTestSupport;
import com.tailtopia.triage.domain.AiConsultOrder;
import com.tailtopia.triage.domain.AiConsultOrderStatus;
import com.tailtopia.triage.domain.DangerLevel;
import com.tailtopia.triage.domain.TriageStatus;
import com.tailtopia.triage.domain.TriageTask;
import com.tailtopia.triage.domain.UnlockChannel;
import com.tailtopia.triage.domain.UnlockMethod;
import com.tailtopia.triage.domain.UnlockSource;
import com.tailtopia.triage.dto.UnlockResponse;
import com.tailtopia.triage.repository.AiConsultOrderRepository;
import com.tailtopia.triage.repository.TriageTaskRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * L0 —— Story 2.3 {@link AiUnlockService} 解锁编排（mock 依赖，无 DB）。核心：三路径 + 入口短路
 * （红色永不锁不扣费 AC6、已解锁不重复扣费 AC5）+ 边界（额度不足/余额不足/非 owner/非 DONE）。
 */
@ExtendWith(MockitoExtension.class)
class AiUnlockServiceTest {

    private static final long USER = 7L;
    private static final long TRIAGE = 5L;
    private static final long PRICE = 10000L;

    @Mock
    private TriageTaskRepository tasks;
    @Mock
    private FreeQuotaService freeQuota;
    @Mock
    private PawCoinWalletService wallet;
    @Mock
    private PaymentIntentService paymentIntents;
    @Mock
    private AiConsultOrderRepository orders;
    @Mock
    private CardTokenGenerator tokenGenerator;
    @Mock
    private PlatformConfigService platformConfig;
    @Mock
    private PricingConfig pricing;
    @Mock
    private PaymentGateway gateway;

    @org.junit.jupiter.api.BeforeEach
    void stubPricing() {
        // 定价读走 PlatformConfigService（9.2）：lenient 默认桩，短路路径（RED/已解锁/非 DONE）不用亦不报未用。
        lenient().when(platformConfig.pricing()).thenReturn(pricing);
        lenient().when(pricing.getAiUnlockPrice()).thenReturn(PRICE);
    }

    private AiUnlockService svc() {
        return new AiUnlockService(tasks, freeQuota, wallet, paymentIntents, orders, tokenGenerator,
                platformConfig, gateway);
    }

    private TriageTask doneTask(DangerLevel level, UnlockSource unlockSource) {
        TriageTask t = TriageTestSupport.task(TRIAGE, USER, TriageStatus.DONE, "咳嗽", null);
        TriageTestSupport.set(t, "dangerLevel", level);
        TriageTestSupport.set(t, "unlockSource", unlockSource);
        when(tasks.findById(TRIAGE)).thenReturn(Optional.of(t));
        return t;
    }

    // ---- AC3：FREE_QUOTA ----

    @Test
    void freeQuotaSuccessUnlocksWithoutOrder() {
        TriageTask t = doneTask(DangerLevel.YELLOW, UnlockSource.LOCKED);
        when(freeQuota.tryConsume(USER)).thenReturn(true);

        UnlockResponse r = svc().unlock(USER, TRIAGE, UnlockMethod.FREE_QUOTA);

        assertThat(r.unlocked()).isTrue();
        assertThat(t.getUnlockSource()).isEqualTo(UnlockSource.FREE_QUOTA);
        verifyNoInteractions(wallet, paymentIntents); // 免费不扣币不建意图
        verify(orders, never()).save(any());          // 免费不建订单
    }

    @Test
    void freeQuotaExhaustedThrowsAndDoesNotUnlock() {
        TriageTask t = doneTask(DangerLevel.YELLOW, UnlockSource.LOCKED);
        when(freeQuota.tryConsume(USER)).thenReturn(false);

        assertThatThrownBy(() -> svc().unlock(USER, TRIAGE, UnlockMethod.FREE_QUOTA))
                .isInstanceOf(AppException.class);
        assertThat(t.getUnlockSource()).isEqualTo(UnlockSource.LOCKED); // 未解锁
        verifyNoInteractions(wallet);
    }

    // ---- AC3：PAWCOIN ----

    @Test
    void pawCoinSuccessDebitsUnlocksAndBuildsCompletedOrder() {
        TriageTask t = doneTask(DangerLevel.GREEN, UnlockSource.LOCKED);
        when(tokenGenerator.generate()).thenReturn("ordtok");

        UnlockResponse r = svc().unlock(USER, TRIAGE, UnlockMethod.PAWCOIN);

        assertThat(r.unlocked()).isTrue();
        assertThat(t.getUnlockSource()).isEqualTo(UnlockSource.PAID);
        verify(wallet).debit(eq(USER), eq(PRICE), eq(PawCoinTxnType.SPEND), eq("AI_UNLOCK"),
                eq(TRIAGE), eq("ai-unlock:" + TRIAGE));
        ArgumentCaptor<AiConsultOrder> cap = ArgumentCaptor.forClass(AiConsultOrder.class);
        verify(orders).save(cap.capture());
        assertThat(cap.getValue().getPayChannel()).isEqualTo(PayChannel.PAWCOIN);
        assertThat(cap.getValue().getAmount()).isEqualTo(PRICE);
    }

    @Test
    void pawCoinInsufficientPropagatesAndDoesNotUnlockOrOrder() {
        TriageTask t = doneTask(DangerLevel.GREEN, UnlockSource.LOCKED);
        // debit 余额不足抛冲突 → 整事务回滚（此处验证不 unlock、不建单）。
        org.mockito.Mockito.doThrow(AppException.conflict("余额不足"))
                .when(wallet).debit(anyLong(), anyLong(), any(), any(), any(), any());

        assertThatThrownBy(() -> svc().unlock(USER, TRIAGE, UnlockMethod.PAWCOIN))
                .isInstanceOf(AppException.class);
        assertThat(t.getUnlockSource()).isEqualTo(UnlockSource.LOCKED);
        verify(orders, never()).save(any());
    }

    // ---- AC4：现金 ----

    @Test
    void cashCreatesIntentAndPendingOrderWithoutUnlocking() {
        TriageTask t = doneTask(DangerLevel.YELLOW, UnlockSource.LOCKED);
        when(tokenGenerator.generate()).thenReturn("ordtok");
        when(paymentIntents.createIntent(eq(USER), eq(PaymentPurpose.AI_UNLOCK), eq(PayChannel.QRIS),
                eq(PRICE), eq("IDR"), eq("ai-unlock:" + TRIAGE)))
                .thenReturn(new PaymentIntentResponse("inttok", "AI_UNLOCK", "QRIS", PRICE, "IDR", "PENDING"));
        when(orders.findByPaymentIntentToken("inttok")).thenReturn(Optional.empty());
        PaymentIntent entity = PaymentIntent.create(USER, PaymentPurpose.AI_UNLOCK, PayChannel.QRIS,
                PRICE, "IDR", "inttok");
        when(paymentIntents.findByToken("inttok")).thenReturn(Optional.of(entity));
        when(gateway.createCharge(any())).thenReturn(new ChargeResult("gwref", "QR-PAYLOAD", Map.of()));

        UnlockResponse r = svc().unlock(USER, TRIAGE, UnlockMethod.QRIS);

        assertThat(r.unlocked()).isFalse();
        assertThat(r.payment()).isNotNull();
        assertThat(r.payment().token()).isEqualTo("inttok");
        assertThat(r.payload()).isEqualTo("QR-PAYLOAD");
        assertThat(t.getUnlockSource()).isEqualTo(UnlockSource.LOCKED); // 未解锁（待到账）
        ArgumentCaptor<AiConsultOrder> cap = ArgumentCaptor.forClass(AiConsultOrder.class);
        verify(orders).save(cap.capture());
        assertThat(cap.getValue().getPaymentIntentToken()).isEqualTo("inttok");
    }

    // ---- AC6 头等：红色永不锁不扣费 ----

    @Test
    void redShortCircuitsWithoutAnyCharge() {
        TriageTask t = doneTask(DangerLevel.RED, UnlockSource.LOCKED);

        UnlockResponse r = svc().unlock(USER, TRIAGE, UnlockMethod.PAWCOIN); // 即便传 PAWCOIN

        assertThat(r.unlocked()).isTrue();
        assertThat(t.getUnlockSource()).isEqualTo(UnlockSource.LOCKED); // 数据不动（响应层放行）
        verifyNoInteractions(freeQuota, wallet, paymentIntents, orders); // 零扣费
    }

    // ---- AC5：已解锁不重复扣费 ----

    @Test
    void alreadyUnlockedShortCircuitsWithoutCharge() {
        TriageTask t = doneTask(DangerLevel.YELLOW, UnlockSource.PAID);

        UnlockResponse r = svc().unlock(USER, TRIAGE, UnlockMethod.PAWCOIN);

        assertThat(r.unlocked()).isTrue();
        verifyNoInteractions(freeQuota, wallet, paymentIntents, orders);
    }

    // ---- 边界 ----

    @Test
    void nonOwnerForbidden() {
        TriageTask others = TriageTestSupport.task(TRIAGE, 999L, TriageStatus.DONE, "x", null);
        when(tasks.findById(TRIAGE)).thenReturn(Optional.of(others));
        assertThatThrownBy(() -> svc().unlock(USER, TRIAGE, UnlockMethod.FREE_QUOTA))
                .isInstanceOf(AppException.class);
    }

    @Test
    void missingTaskForbidden() {
        when(tasks.findById(TRIAGE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc().unlock(USER, TRIAGE, UnlockMethod.FREE_QUOTA))
                .isInstanceOf(AppException.class);
    }

    @Test
    void notDoneConflict() {
        TriageTask proc = TriageTestSupport.task(TRIAGE, USER, TriageStatus.PROCESSING, "x", null);
        when(tasks.findById(TRIAGE)).thenReturn(Optional.of(proc));
        assertThatThrownBy(() -> svc().unlock(USER, TRIAGE, UnlockMethod.FREE_QUOTA))
                .isInstanceOf(AppException.class);
    }

    // ---- AC4：现金到账 completeCashUnlock（幂等/异常） ----

    private AiConsultOrder pendingOrder() {
        return AiConsultOrder.pendingCash("ord", USER, TRIAGE, PRICE, PayChannel.QRIS, "inttok");
    }

    @Test
    void completeCashUnlockUnlocksPendingOrder() {
        AiConsultOrder order = pendingOrder();
        when(orders.findByPaymentIntentToken("inttok")).thenReturn(Optional.of(order));
        TriageTask t = TriageTestSupport.task(TRIAGE, USER, TriageStatus.DONE, "x", null);
        TriageTestSupport.set(t, "dangerLevel", DangerLevel.YELLOW);
        TriageTestSupport.set(t, "unlockSource", UnlockSource.LOCKED);
        when(tasks.findById(TRIAGE)).thenReturn(Optional.of(t));

        svc().completeCashUnlock("inttok");

        assertThat(t.getUnlockSource()).isEqualTo(UnlockSource.PAID);
        assertThat(t.getUnlockChannel()).isEqualTo(UnlockChannel.QRIS);
        assertThat(order.getStatus()).isEqualTo(AiConsultOrderStatus.COMPLETED);
        assertThat(order.getPaidAt()).isNotNull();
    }

    @Test
    void completeCashUnlockIdempotentWhenAlreadyCompleted() {
        AiConsultOrder order = pendingOrder();
        order.markCompleted(Instant.now());
        when(orders.findByPaymentIntentToken("inttok")).thenReturn(Optional.of(order));

        svc().completeCashUnlock("inttok");

        verify(tasks, never()).findById(anyLong()); // 短路不查 task、不重复解锁
    }

    @Test
    void completeCashUnlockNoOrderIsNoOp() {
        when(orders.findByPaymentIntentToken("x")).thenReturn(Optional.empty());
        svc().completeCashUnlock("x"); // 到账但无单 → 不抛、不 crash
        verify(tasks, never()).findById(anyLong());
    }

    @Test
    void completeCashUnlockTaskMissingMarksAbnormal() {
        AiConsultOrder order = pendingOrder();
        when(orders.findByPaymentIntentToken("inttok")).thenReturn(Optional.of(order));
        when(tasks.findById(TRIAGE)).thenReturn(Optional.empty());

        svc().completeCashUnlock("inttok");

        assertThat(order.getStatus()).isEqualTo(AiConsultOrderStatus.ABNORMAL);
    }

    @Test
    void completeCashUnlockRedMarksCompletedWithoutReunlock() {
        AiConsultOrder order = pendingOrder();
        when(orders.findByPaymentIntentToken("inttok")).thenReturn(Optional.of(order));
        TriageTask t = TriageTestSupport.task(TRIAGE, USER, TriageStatus.DONE, "x", null);
        TriageTestSupport.set(t, "dangerLevel", DangerLevel.RED);
        TriageTestSupport.set(t, "unlockSource", UnlockSource.LOCKED);
        when(tasks.findById(TRIAGE)).thenReturn(Optional.of(t));

        svc().completeCashUnlock("inttok");

        assertThat(order.getStatus()).isEqualTo(AiConsultOrderStatus.COMPLETED);
        assertThat(t.getUnlockSource()).isEqualTo(UnlockSource.LOCKED); // 红色本就放行，不改数据
    }
}
