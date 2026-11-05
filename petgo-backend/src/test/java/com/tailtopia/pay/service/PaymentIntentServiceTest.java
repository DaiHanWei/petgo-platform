package com.tailtopia.pay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.pay.domain.PaymentFailureCategory;
import com.tailtopia.pay.event.PaymentIntentFailedEvent;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：意图 service 幂等（mock repo/idempotency/redis/events）。验建单幂等命中、回调到账推进 + 发事件、
 * 已终态二次回调幂等 no-op、PENDING 不推进。
 */
@ExtendWith(MockitoExtension.class)
class PaymentIntentServiceTest {

    @Mock
    PaymentIntentRepository intents;
    @Mock
    IdempotencyService idempotency;
    @Mock
    CardTokenGenerator tokenGenerator;
    @Mock
    RedisRateLimiter rateLimiter;
    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;
    @Mock
    ApplicationEventPublisher events;

    private PaymentIntentService service() {
        return new PaymentIntentService(intents, idempotency, tokenGenerator, rateLimiter, redis, events);
    }

    private PaymentIntent persisted(long id, PaymentPurpose purpose, String token) {
        PaymentIntent p = PaymentIntent.create(7L, purpose, PayChannel.QRIS, 10000L, "IDR", token);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    @Test
    void statusOfReturnsStatusForOwner() {
        // persisted 的 userId=7（PaymentIntent.create(7L,...)），初始 PENDING。
        when(intents.findByPublicToken("tok-s")).thenReturn(
                Optional.of(persisted(9L, PaymentPurpose.PAWCOIN_TOPUP, "tok-s")));
        assertThat(service().statusOf(7L, "tok-s")).isEqualTo(
                com.tailtopia.pay.domain.PaymentStatus.PENDING);
    }

    @Test
    void statusOfRejectsNonOwnerAndMissingWithNotFound() {
        when(intents.findByPublicToken("tok-s")).thenReturn(
                Optional.of(persisted(9L, PaymentPurpose.PAWCOIN_TOPUP, "tok-s")));
        // 越权：非本人 token → notFound（不泄漏存在性）
        assertThatThrownBy(() -> service().statusOf(8L, "tok-s")).isInstanceOf(AppException.class);
        // 不存在 → notFound
        when(intents.findByPublicToken("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().statusOf(7L, "nope")).isInstanceOf(AppException.class);
    }

    @Test
    void createIntentReplaysExistingOnIdempotencyHit() {
        PaymentIntent existing = persisted(55L, PaymentPurpose.PAWCOIN_TOPUP, "tok-existing");
        when(idempotency.findResourceId("key-1")).thenReturn(Optional.of(55L));
        when(intents.findById(55L)).thenReturn(Optional.of(existing));

        PaymentIntentResponse resp = service().createIntent(
                7L, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS, 10000L, "IDR", "key-1");

        assertThat(resp.token()).isEqualTo("tok-existing");
        verify(intents, never()).save(any());          // 命中不重复建单
        verify(idempotency, never()).store(anyString(), anyLong());
        verify(rateLimiter).check(eq("rl:pay:create:7"), anyInt(), any(Duration.class));
    }

    @Test
    void createIntentPersistsAndStoresKeyOnMiss() {
        when(idempotency.findResourceId("key-2")).thenReturn(Optional.empty());
        when(tokenGenerator.generate()).thenReturn("tok-new");
        when(intents.save(any(PaymentIntent.class))).thenAnswer(inv -> {
            PaymentIntent p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 88L);
            return p;
        });

        PaymentIntentResponse resp = service().createIntent(
                7L, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS, 10000L, "IDR", "key-2");

        assertThat(resp.token()).isEqualTo("tok-new");
        assertThat(resp.status()).isEqualTo("PENDING");
        verify(idempotency).store("key-2", 88L);
    }

    @Test
    void applyCallbackPaidAdvancesAndPublishesEvent() {
        PaymentIntent intent = persisted(100L, PaymentPurpose.PAWCOIN_TOPUP, "tok-100");
        when(intents.findByGatewayRef("tx-1")).thenReturn(Optional.of(intent));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(intents.saveAndFlush(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        service().applyCallback(new PaymentCallback("tok-100", "tx-1", GatewayStatus.PAID, Map.of("a", 1)));

        assertThat(intent.getStatus().name()).isEqualTo("PAID");
        assertThat(intent.getGatewayRef()).isEqualTo("tx-1");
        verify(events).publishEvent(any(PaymentIntentPaidEvent.class));
    }

    @Test
    void applyCallbackIsIdempotentWhenAlreadyTerminal() {
        PaymentIntent intent = persisted(101L, PaymentPurpose.PAWCOIN_TOPUP, "tok-101");
        intent.attachGatewayRef("tx-2", null);
        intent.markPaid(null); // 已终态
        when(intents.findByGatewayRef("tx-2")).thenReturn(Optional.of(intent));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        service().applyCallback(new PaymentCallback("tok-101", "tx-2", GatewayStatus.PAID, Map.of()));

        // 二次回调不重复推进、不重复发事件
        verify(intents, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void applyCallbackIgnoresPendingStatus() {
        service().applyCallback(new PaymentCallback("tok-x", "tx-x", GatewayStatus.PENDING, Map.of()));
        verify(intents, never()).findByGatewayRef(anyString());
        verify(intents, never()).saveAndFlush(any());
    }

    @Test
    void failPendingMarksLatestPendingFailed() {
        // 用户取消问诊 → 联动置该用户最新 PENDING VET_CONSULT 意图为 FAILED（不残留后台列表）。
        PaymentIntent intent = persisted(200L, PaymentPurpose.VET_CONSULT, "tok-200");
        when(intents.findFirstByUserIdAndPurposeAndStatusOrderByCreatedAtDesc(
                7L, PaymentPurpose.VET_CONSULT, com.tailtopia.pay.domain.PaymentStatus.PENDING))
                .thenReturn(Optional.of(intent));
        when(intents.saveAndFlush(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        service().failPending(7L, PaymentPurpose.VET_CONSULT);

        assertThat(intent.getStatus().name()).isEqualTo("FAILED");
        verify(intents).saveAndFlush(intent);
    }

    @Test
    void failPendingNoopWhenNoPending() {
        when(intents.findFirstByUserIdAndPurposeAndStatusOrderByCreatedAtDesc(
                7L, PaymentPurpose.VET_CONSULT, com.tailtopia.pay.domain.PaymentStatus.PENDING))
                .thenReturn(Optional.empty());

        service().failPending(7L, PaymentPurpose.VET_CONSULT);

        verify(intents, never()).saveAndFlush(any());
    }

    // ===== 网关下单失败收口（2026-09-21 GemPay 超时事故）=====

    @Test
    void failChargeAttemptMarksRefLessPendingFailedAndPublishesDeclined() {
        PaymentIntent pending = persisted(60L, PaymentPurpose.SHOP_ORDER, "tok-fail");
        when(intents.findByPublicToken("tok-fail")).thenReturn(Optional.of(pending));

        service().failChargeAttempt("tok-fail");

        assertThat(pending.getStatus()).isEqualTo(com.tailtopia.pay.domain.PaymentStatus.FAILED);
        assertThat(pending.getGatewayMeta()).containsEntry("reason", "GATEWAY_CHARGE_FAILED");
        verify(intents).saveAndFlush(pending);
        org.mockito.ArgumentCaptor<Object> ev = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue()).isInstanceOfSatisfying(PaymentIntentFailedEvent.class, e ->
                assertThat(e.failureCategory()).isEqualTo(PaymentFailureCategory.GATEWAY_DECLINED));
    }

    @Test
    void failChargeAttemptIsNoopWhenAlreadyChargedOrTerminal() {
        PaymentIntent charged = persisted(61L, PaymentPurpose.SHOP_ORDER, "tok-charged");
        charged.attachGatewayRef("gw-1", Map.of("payload", "qr"));  // 并发的另一请求已下单成功
        when(intents.findByPublicToken("tok-charged")).thenReturn(Optional.of(charged));
        PaymentIntent paid = persisted(62L, PaymentPurpose.SHOP_ORDER, "tok-paid");
        paid.markFailed(Map.of("reason", "CANCELLED"));
        when(intents.findByPublicToken("tok-paid")).thenReturn(Optional.of(paid));

        service().failChargeAttempt("tok-charged");
        service().failChargeAttempt("tok-paid");

        assertThat(charged.getStatus()).isEqualTo(com.tailtopia.pay.domain.PaymentStatus.PENDING);
        verify(intents, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void createIntentMintsNewTokenAfterChargeFailure() {
        PaymentIntent failed = persisted(63L, PaymentPurpose.ID_HD, "tok-old");
        failed.markFailed(Map.of("reason", "GATEWAY_CHARGE_FAILED"));
        when(idempotency.findResourceId("id-hd:1")).thenReturn(Optional.of(63L));
        when(intents.findById(63L)).thenReturn(Optional.of(failed));
        when(tokenGenerator.generate()).thenReturn("tok-new");
        when(intents.save(any(PaymentIntent.class))).thenAnswer(inv -> {
            PaymentIntent p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 64L);
            return p;
        });

        PaymentIntentResponse resp = service().createIntent(
                7L, PaymentPurpose.ID_HD, PayChannel.QRIS, 1000L, "IDR", "id-hd:1");

        // 新 request_id：不拿失败单的旧号去撞网关的重复单号（P02）。
        assertThat(resp.token()).isEqualTo("tok-new");
        verify(idempotency).store("id-hd:1", 64L);
    }

    @Test
    void createIntentTreatsDanglingIdempotencyMappingAsMiss() {
        // Redis 映射还在、库里那行已随事务回滚 → 以前 404，现在新建。
        when(idempotency.findResourceId("key-dangling")).thenReturn(Optional.of(404L));
        when(intents.findById(404L)).thenReturn(Optional.empty());
        when(tokenGenerator.generate()).thenReturn("tok-fresh");
        when(intents.save(any(PaymentIntent.class))).thenAnswer(inv -> {
            PaymentIntent p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 65L);
            return p;
        });

        PaymentIntentResponse resp = service().createIntent(
                7L, PaymentPurpose.ID_HD, PayChannel.QRIS, 1000L, "IDR", "key-dangling");

        assertThat(resp.token()).isEqualTo("tok-fresh");
        verify(idempotency).store("key-dangling", 65L);
    }

    @Test
    void findReusablePendingSkipsIntentWithoutGatewayRef() {
        PaymentIntent refLess = PaymentIntent.create(7L, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS,
                10000L, "IDR", "tok-noref", java.time.Instant.now().plusSeconds(3600));
        when(intents.findFirstByUserIdAndPurposeAndChannelAndAmountAndStatusOrderByCreatedAtDesc(
                7L, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS, 10000L,
                com.tailtopia.pay.domain.PaymentStatus.PENDING)).thenReturn(Optional.of(refLess));

        assertThat(service().findReusablePending(7L, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS, 10000L))
                .isEmpty();
    }

    // ---------- 2026-09-25 code review #1：付了钱不履约的并发竞态 ----------

    @Test
    void attachChargeRefusesTerminalIntentSoNoQrIsHandedOut() {
        // 快请求被网关判重复单号 → failChargeAttempt 置 FAILED；慢请求随后拿到二维码来回填
        PaymentIntent p = persisted(9L, PaymentPurpose.SHOP_ORDER, "tok-race");
        p.markFailed(java.util.Map.of("reason", "GATEWAY_CHARGE_FAILED"));
        when(intents.findByPublicToken("tok-race")).thenReturn(Optional.of(p));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().attachCharge("tok-race", "gw-1",
                        java.util.Map.of("payload", "QR")))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class);
        assertThat(p.getGatewayRef()).as("终态意图不得回填网关单号").isNull();
        org.mockito.Mockito.verify(intents, org.mockito.Mockito.never()).saveAndFlush(p);
    }

    @Test
    void attachChargeStillWorksForPendingAndIsIdempotent() {
        PaymentIntent p = persisted(9L, PaymentPurpose.SHOP_ORDER, "tok-ok");
        when(intents.findByPublicToken("tok-ok")).thenReturn(Optional.of(p));

        service().attachCharge("tok-ok", "gw-1", java.util.Map.of("payload", "QR"));
        assertThat(p.getGatewayRef()).isEqualTo("gw-1");

        // 已回填 → 幂等短路，不因之后的状态判断报错
        service().attachCharge("tok-ok", "gw-2", java.util.Map.of());
        assertThat(p.getGatewayRef()).isEqualTo("gw-1");
    }

    // ===== v1.4.2 shop Story 3.3：码失效判据 / 关单后到账 / attachCharge 重载 =====

    /** 带建单时刻的意图（created_at 由 @PrePersist 写，L0 用反射拨）。 */
    private PaymentIntent persistedAt(long id, PaymentPurpose purpose, String token, java.time.Instant createdAt,
            java.time.Instant expiresAt) {
        PaymentIntent p = PaymentIntent.create(7L, purpose, PayChannel.QRIS, 10000L, "IDR", token, expiresAt);
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "createdAt", createdAt);
        return p;
    }

    private void stubDedup(boolean fresh) {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(fresh);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("🔴 电商意图先 FAILED（关单）后收到 PAID —— 即使 Redis 去重键已被 FAILED 回调占用 → PAID + paid_after_close + 发 PaidEvent")
    void applyResultShopFailedThenPaidMarksPaidAfterClose() {
        PaymentIntent intent = persisted(300L, PaymentPurpose.SHOP_ORDER, "tok-300");
        intent.attachGatewayRef("gw-300", null);
        intent.markFailed(Map.of("reason", "TIMEOUT"));
        when(intents.findByGatewayRef("gw-300")).thenReturn(Optional.of(intent));
        when(intents.saveAndFlush(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.lenient().when(redis.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.lenient().when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);   // 去重键已存在

        service().applyResult(new PaymentCallback("tok-300", "gw-300", GatewayStatus.PAID, Map.of("s", "ok")));

        assertThat(intent.getStatus()).isEqualTo(com.tailtopia.pay.domain.PaymentStatus.PAID);
        assertThat(intent.isPaidAfterClose()).isTrue();
        verify(events).publishEvent(any(PaymentIntentPaidEvent.class));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("电商 EXPIRED + PAID（gateway_ref 为空，按 order_id 找到）→ 顺带写入 gateway_ref")
    void applyResultShopExpiredThenPaidFillsGatewayRef() {
        PaymentIntent intent = persisted(301L, PaymentPurpose.SHOP_ORDER, "tok-301");
        intent.markExpired(null);
        when(intents.findByGatewayRef("gw-301")).thenReturn(Optional.empty());
        when(intents.findByPublicToken("tok-301")).thenReturn(Optional.of(intent));
        when(intents.saveAndFlush(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        service().applyResult(new PaymentCallback("tok-301", "gw-301", GatewayStatus.PAID, Map.of()));

        assertThat(intent.getGatewayRef()).isEqualTo("gw-301");
        assertThat(intent.isPaidAfterClose()).isTrue();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("电商终态意图收到 FAILED / EXPIRED 回调 → 仍按原逻辑短路")
    void applyResultShopTerminalIgnoresNonPaid() {
        PaymentIntent intent = persisted(302L, PaymentPurpose.SHOP_ORDER, "tok-302");
        intent.attachGatewayRef("gw-302", null);
        intent.markFailed(null);
        when(intents.findByGatewayRef("gw-302")).thenReturn(Optional.of(intent));
        stubDedup(false);

        service().applyResult(new PaymentCallback("tok-302", "gw-302", GatewayStatus.EXPIRED, Map.of()));

        verify(intents, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("AC10.1：GIFT 意图 EXPIRED 后收到 PAID → 不推进、不发事件")
    void applyResultIgnoresTerminalGiftIntent() {
        PaymentIntent intent = persisted(303L, PaymentPurpose.GIFT, "tok-303");
        intent.attachGatewayRef("gw-303", null);
        intent.markExpired(null);
        when(intents.findByGatewayRef("gw-303")).thenReturn(Optional.of(intent));
        stubDedup(true);

        service().applyResult(new PaymentCallback("tok-303", "gw-303", GatewayStatus.PAID, Map.of()));

        assertThat(intent.getStatus()).isEqualTo(com.tailtopia.pay.domain.PaymentStatus.EXPIRED);
        assertThat(intent.isPaidAfterClose()).isFalse();
        verify(intents, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("AC10.2：PAWCOIN_TOPUP 意图 FAILED 后收到 PAID → 不推进、不发事件")
    void applyResultIgnoresTerminalTopupIntent() {
        PaymentIntent intent = persisted(304L, PaymentPurpose.PAWCOIN_TOPUP, "tok-304");
        intent.attachGatewayRef("gw-304", null);
        intent.markFailed(null);
        when(intents.findByGatewayRef("gw-304")).thenReturn(Optional.of(intent));
        stubDedup(false);

        service().applyResult(new PaymentCallback("tok-304", "gw-304", GatewayStatus.PAID, Map.of()));

        assertThat(intent.getStatus()).isEqualTo(com.tailtopia.pay.domain.PaymentStatus.FAILED);
        verify(events, never()).publishEvent(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("applyCallback 是 applyResult 的一行委托（回调控制器 / 支付模拟不改）")
    void applyCallbackDelegatesToApplyResult() {
        PaymentIntentService spy = org.mockito.Mockito.spy(service());
        PaymentCallback cb = new PaymentCallback("t", "r", GatewayStatus.PENDING, Map.of());
        spy.applyCallback(cb);
        verify(spy).applyResult(cb);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("🔴 B1 ②：SHOP_ORDER 意图 expires_at 已过但 created_at+65 未到 → 全局扫描不动")
    void expireSkipsShopIntentBeforeProviderExpiry() {
        java.time.Instant now = java.time.Instant.now();
        PaymentIntent shop = persistedAt(310L, PaymentPurpose.SHOP_ORDER, "tok-310",
                now.minus(Duration.ofMinutes(61)), now.minus(Duration.ofMinutes(1)));
        when(intents.findByStatusAndExpiresAtBefore(any(), any(), any())).thenReturn(java.util.List.of(shop));

        assertThat(service().expireOverduePending(10)).isZero();
        assertThat(shop.getStatus()).isEqualTo(com.tailtopia.pay.domain.PaymentStatus.PENDING);
        verify(events, never()).publishEvent(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("AC10.2 回归：PAWCOIN_TOPUP 61 分钟 → EXPIRED（充值 60 分钟窗口不变）")
    void expireStillExpiresTopupAt60Min() {
        java.time.Instant now = java.time.Instant.now();
        PaymentIntent topup = persistedAt(311L, PaymentPurpose.PAWCOIN_TOPUP, "tok-311",
                now.minus(Duration.ofMinutes(61)), now.minus(Duration.ofMinutes(1)));
        when(intents.findByStatusAndExpiresAtBefore(any(), any(), any())).thenReturn(java.util.List.of(topup));

        assertThat(service().expireOverduePending(10)).isEqualTo(1);
        assertThat(topup.getStatus()).isEqualTo(com.tailtopia.pay.domain.PaymentStatus.EXPIRED);
        verify(events).publishEvent(any(PaymentIntentFailedEvent.class));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("SHOP_ORDER 意图码已失效（provider_expires_at 已过）→ 全局扫描置 EXPIRED")
    void expireShopIntentAfterProviderExpiry() {
        java.time.Instant now = java.time.Instant.now();
        PaymentIntent shop = persistedAt(312L, PaymentPurpose.SHOP_ORDER, "tok-312",
                now.minus(Duration.ofMinutes(70)), now.minus(Duration.ofMinutes(10)));
        shop.attachGatewayRef("gw-312", null);
        shop.markProviderIssued(now.minus(Duration.ofMinutes(1)));
        when(intents.findByStatusAndExpiresAtBefore(any(), any(), any())).thenReturn(java.util.List.of(shop));

        assertThat(service().expireOverduePending(10)).isEqualTo(1);
        assertThat(shop.getStatus()).isEqualTo(com.tailtopia.pay.domain.PaymentStatus.EXPIRED);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("🔴 B1 ③：statusOf 对 SHOP_ORDER 只读 —— 过窗也不置 EXPIRED")
    void statusOfIsReadOnlyForShopIntent() {
        java.time.Instant now = java.time.Instant.now();
        PaymentIntent shop = persistedAt(320L, PaymentPurpose.SHOP_ORDER, "tok-320",
                now.minus(Duration.ofMinutes(61)), now.minus(Duration.ofMinutes(1)));
        when(intents.findByPublicToken("tok-320")).thenReturn(Optional.of(shop));

        assertThat(service().statusOf(7L, "tok-320")).isEqualTo(com.tailtopia.pay.domain.PaymentStatus.PENDING);
        verify(intents, never()).saveAndFlush(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("回归：statusOf 对 PAWCOIN_TOPUP 仍就地懒过期")
    void statusOfStillExpiresTopupLazily() {
        java.time.Instant now = java.time.Instant.now();
        PaymentIntent topup = persistedAt(321L, PaymentPurpose.PAWCOIN_TOPUP, "tok-321",
                now.minus(Duration.ofMinutes(61)), now.minus(Duration.ofMinutes(1)));
        when(intents.findByPublicToken("tok-321")).thenReturn(Optional.of(topup));

        assertThat(service().statusOf(7L, "tok-321")).isEqualTo(com.tailtopia.pay.domain.PaymentStatus.EXPIRED);
        verify(intents).saveAndFlush(topup);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("AC2.3：attachCharge(…, providerTtl) → provider_expires_at = expires_at ≈ now + 65 分钟")
    void attachChargeWithProviderTtlSetsBothExpiries() {
        java.time.Instant now = java.time.Instant.now();
        PaymentIntent p = persistedAt(330L, PaymentPurpose.SHOP_ORDER, "tok-330", now, now.plus(Duration.ofMinutes(65)));
        when(intents.findByPublicToken("tok-330")).thenReturn(Optional.of(p));

        service().attachCharge("tok-330", "gw-330", Map.of("payload", "QR"), Duration.ofMinutes(65));

        assertThat(p.getGatewayRef()).isEqualTo("gw-330");
        assertThat(p.getProviderExpiresAt()).isNotNull().isEqualTo(p.getExpiresAt());
        assertThat(Duration.between(now, p.getProviderExpiresAt()).toMinutes()).isBetween(64L, 65L);
        verify(intents).saveAndFlush(p);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("三参 attachCharge 行为不变：不写 provider_expires_at、不改 expires_at")
    void attachChargeThreeArgUnchanged() {
        java.time.Instant exp = java.time.Instant.now().plus(Duration.ofMinutes(60));
        PaymentIntent p = persistedAt(331L, PaymentPurpose.PAWCOIN_TOPUP, "tok-331", java.time.Instant.now(), exp);
        when(intents.findByPublicToken("tok-331")).thenReturn(Optional.of(p));

        service().attachCharge("tok-331", "gw-331", Map.of());

        assertThat(p.getProviderExpiresAt()).isNull();
        assertThat(p.getExpiresAt()).isEqualTo(exp);
    }
}
