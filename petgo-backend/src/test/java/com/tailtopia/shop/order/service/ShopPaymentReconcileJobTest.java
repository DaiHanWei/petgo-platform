package com.tailtopia.shop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.pay.service.PaymentReconciliationService;
import com.tailtopia.shared.pay.ChargeQueryResult;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/** L0：v1.4.2 shop Story 3.3 AC8 —— 定时查单（Redis 锁防重入、事务外查、幂等）。 */
class ShopPaymentReconcileJobTest {

    private final PaymentIntentService intents = mock(PaymentIntentService.class);
    private final PaymentReconciliationService reconciliation = mock(PaymentReconciliationService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final ShopPaymentReconcileJob job = new ShopPaymentReconcileJob(intents, reconciliation, redis);

    {
        when(redis.opsForValue()).thenReturn(ops);
    }

    private static PaymentIntent pending(long id) {
        Instant created = Instant.now().minus(Duration.ofMinutes(10));
        return ShopPaymentTestRig.intent(id, "pi-" + id, created, "gw-" + id, created.plus(Duration.ofMinutes(65)));
    }

    @Test
    @DisplayName("拿不到锁 → 跳过本轮，不查候选")
    void skipsWhenLockHeld() {
        when(ops.setIfAbsent(eq("lock:shop-payment-reconcile"), anyString(), eq(Duration.ofMillis(110_000))))
                .thenReturn(false);
        assertThat(job.run()).isEqualTo(-1);
        verify(intents, never()).findShopReconcileCandidates(any());
    }

    @Test
    @DisplayName("拿到锁 → PAID 走 applyResult；结束按 uuid 比对释放锁")
    @SuppressWarnings("unchecked")
    void paidAppliesAndReleasesLock() {
        when(ops.setIfAbsent(eq("lock:shop-payment-reconcile"), anyString(), any(Duration.class))).thenReturn(true);
        PaymentIntent pi = pending(1L);
        when(intents.findShopReconcileCandidates(any())).thenReturn(List.of(pi));
        PaymentCallback cb = new PaymentCallback("pi-1", "gw-1", GatewayStatus.PAID, Map.of());
        when(reconciliation.query(pi)).thenReturn(ChargeQueryResult.paid(cb));

        assertThat(job.run()).isEqualTo(1);

        verify(intents).applyResult(cb);
        verify(redis).execute(any(RedisScript.class), eq(List.of("lock:shop-payment-reconcile")), anyString());
    }

    @Test
    @DisplayName("网关 EXPIRED 且意图仍 PENDING → applyResult 推进；已终态意图 + 网关 EXPIRED → 不动；UNKNOWN / PENDING 不动")
    void terminalNotPaidOnlyForPendingIntent() {
        PaymentIntent p = pending(2L);
        PaymentIntent closed = pending(3L);
        closed.markFailed(Map.of("reason", "TIMEOUT"));
        PaymentIntent unk = pending(4L);
        PaymentIntent pend = pending(5L);
        when(intents.findShopReconcileCandidates(any())).thenReturn(List.of(p, closed, unk, pend));
        PaymentCallback e2 = new PaymentCallback(null, "gw-2", GatewayStatus.EXPIRED, Map.of());
        PaymentCallback e3 = new PaymentCallback(null, "gw-3", GatewayStatus.EXPIRED, Map.of());
        when(reconciliation.query(p)).thenReturn(ChargeQueryResult.notPaid(e2));
        when(reconciliation.query(closed)).thenReturn(ChargeQueryResult.notPaid(e3));
        when(reconciliation.query(unk)).thenReturn(ChargeQueryResult.unknown());
        when(reconciliation.query(pend)).thenReturn(ChargeQueryResult.notPaid(
                new PaymentCallback(null, "gw-5", GatewayStatus.PENDING, Map.of())));

        assertThat(job.reconcileBatch(Instant.now())).isEqualTo(1);

        verify(intents).applyResult(e2);
        verify(intents, never()).applyResult(e3);
        verify(intents, times(1)).applyResult(any());
    }

    @Test
    @DisplayName("🔴 关单 23 小时的 FAILED 意图查到 PAID → applyResult（迟到款）；第二轮意图已 PAID → 不再推进（无副作用）")
    void lateFailedIntentPaidThenIdempotent() {
        PaymentIntent closed = pending(6L);
        closed.markFailed(Map.of("reason", "TIMEOUT"));
        when(intents.findShopReconcileCandidates(any())).thenReturn(List.of(closed));
        PaymentCallback cb = new PaymentCallback("pi-6", "gw-6", GatewayStatus.PAID, Map.of());
        when(reconciliation.query(closed)).thenReturn(ChargeQueryResult.paid(cb));

        assertThat(job.reconcileBatch(Instant.now())).isEqualTo(1);
        verify(intents).applyResult(cb);

        // 第二轮：意图已 PAID（applyResult 的 shopLate 只在非 PAID 时成立，这里连调都不调）
        PaymentIntent paid = pending(7L);
        paid.markPaid(null);
        when(intents.findShopReconcileCandidates(any())).thenReturn(List.of(paid));
        when(reconciliation.query(paid)).thenReturn(ChargeQueryResult.paid(
                new PaymentCallback("pi-7", "gw-7", GatewayStatus.PAID, Map.of())));
        assertThat(job.reconcileBatch(Instant.now())).isZero();
    }

    @Test
    @DisplayName("单笔异常不阻断；整轮异常不让调度线程崩")
    void failuresAreContained() {
        PaymentIntent a = pending(8L);
        PaymentIntent b = pending(9L);
        when(intents.findShopReconcileCandidates(any())).thenReturn(List.of(a, b));
        when(reconciliation.query(a)).thenThrow(new IllegalStateException("boom"));
        PaymentCallback cb = new PaymentCallback("pi-9", "gw-9", GatewayStatus.PAID, Map.of());
        when(reconciliation.query(b)).thenReturn(ChargeQueryResult.paid(cb));
        assertThat(job.reconcileBatch(Instant.now())).isEqualTo(1);

        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenThrow(new IllegalStateException("redis"));
        job.scheduled();    // 不抛
    }

    @Test
    @DisplayName("候选 SQL 常量：SHOP_ORDER、已出码、3 分钟、PENDING 或 24 小时内 FAILED / EXPIRED、按 created_at 升序 LIMIT 100")
    void candidateSqlText() {
        String sql = PaymentIntentRepository.SHOP_RECONCILE_CANDIDATES_SQL.replaceAll("\\s+", " ");
        assertThat(sql).contains("purpose = 'SHOP_ORDER' AND gateway_ref IS NOT NULL")
                .contains("INTERVAL '3 minutes'")
                .contains("status = 'PENDING'")
                .contains("status IN ('FAILED','EXPIRED') AND updated_at >")
                .contains("INTERVAL '24 hours'")
                .contains("ORDER BY (status = 'PENDING') DESC, created_at ASC LIMIT 100");
        // 复审修复①：筛选口径不变，只调排序（PENDING 优先不被积压的终态意图饿死）
        assertThat(sql).doesNotContain("ORDER BY created_at ASC");
    }
}
