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
}
