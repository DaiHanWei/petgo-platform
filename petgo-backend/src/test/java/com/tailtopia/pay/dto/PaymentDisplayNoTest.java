package com.tailtopia.pay.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（bug 326 增补）：可读支付号 = PAY用途-建单日(WIB)-id 补零 6 位；未落库（无 id）回 null 由调用方退 token。 */
class PaymentDisplayNoTest {

    @Test
    void formatsPurposePrefixWibDateAndPaddedId() {
        PaymentIntent p = PaymentIntent.create(1L, PaymentPurpose.VET_CONSULT, PayChannel.QRIS,
                50_000L, "IDR", "tok");
        ReflectionTestUtils.setField(p, "id", 123L);
        // UTC 17:30 = WIB 次日 00:30 —— 断言日期按 WIB 计（对齐 OrderDisplayNo/后台 AdminTime 口径）。
        ReflectionTestUtils.setField(p, "createdAt", Instant.parse("2026-07-26T17:30:00Z"));

        assertThat(PaymentDisplayNo.of(p)).isEqualTo("PAYVET-20260727-000123");
    }

    @Test
    void eachPurposeHasDistinctPayPrefix() {
        PaymentIntent p = PaymentIntent.create(1L, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS,
                10_000L, "IDR", "tok");
        ReflectionTestUtils.setField(p, "id", 7L);
        ReflectionTestUtils.setField(p, "createdAt", Instant.parse("2026-07-27T01:00:00Z"));
        assertThat(PaymentDisplayNo.of(p)).startsWith("PAYTOPUP-").endsWith("-000007");
    }

    @Test
    void unsavedIntentYieldsNullSoCallerFallsBackToToken() {
        PaymentIntent p = PaymentIntent.create(1L, PaymentPurpose.ID_HD, PayChannel.QRIS,
                20_000L, "IDR", "tok");
        assertThat(PaymentDisplayNo.of(p)).isNull();
    }
}
