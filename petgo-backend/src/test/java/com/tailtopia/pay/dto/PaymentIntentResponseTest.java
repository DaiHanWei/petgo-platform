package com.tailtopia.pay.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（V1.4.1 QRIS 支付页）：支付意图响应带出付款窗过期时刻，供客户端倒计时；无过期的意图为 null。 */
class PaymentIntentResponseTest {

    @Test
    void carriesExpiresAtFromIntent() {
        Instant exp = Instant.parse("2026-10-30T05:24:00Z");
        PaymentIntent p = PaymentIntent.create(1L, PaymentPurpose.GIFT, PayChannel.QRIS, 52_000L, "IDR",
                "tok", exp);
        ReflectionTestUtils.setField(p, "id", 42L);
        ReflectionTestUtils.setField(p, "createdAt", Instant.parse("2026-10-30T04:24:00Z"));

        PaymentIntentResponse r = PaymentIntentResponse.of(p);

        assertThat(r.expiresAt()).isEqualTo(exp);
        assertThat(r.amount()).isEqualTo(52_000L);
        assertThat(r.displayNo()).startsWith("PAYGIFT-");
    }

    @Test
    void legacyConstructorLeavesExpiresAtNull() {
        assertThat(new PaymentIntentResponse("t", "GIFT", "QRIS", 1L, "IDR", "PENDING", "PAYGIFT-x").expiresAt())
                .isNull();
    }
}
