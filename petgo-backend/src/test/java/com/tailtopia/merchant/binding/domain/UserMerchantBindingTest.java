package com.tailtopia.merchant.binding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：绑定时刻截到微秒，绑定响应与之后读出的 bound_at 一致（timestamptz 只存到微秒并四舍五入）。 */
class UserMerchantBindingTest {

    @Test
    @DisplayName("bound_at 截到微秒，不向上进位")
    void boundAtTruncatedToMicros() {
        UserMerchantBinding b = UserMerchantBinding.of(1L, 2L, Instant.parse("2026-10-25T08:27:40.320242973Z"));
        assertThat(b.getBoundAt()).isEqualTo(Instant.parse("2026-10-25T08:27:40.320242Z"));
    }
}
