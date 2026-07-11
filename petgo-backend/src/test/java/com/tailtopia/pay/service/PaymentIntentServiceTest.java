package com.tailtopia.pay.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
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
}
