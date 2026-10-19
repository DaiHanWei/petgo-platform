package com.tailtopia.gift.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** V1.4.0 gift Story 1.1 · AC10.1（L0）：四把幂等键的形状（架构 §5）。 */
class GiftIdemKeysTest {

    @Test
    void sendKeyIsScopedBySenderAndRequestId() {
        assertThat(GiftIdemKeys.send(42L, "req-uuid-1")).isEqualTo("GIFT:42:req-uuid-1");
        assertThat(GiftIdemKeys.send(43L, "req-uuid-1")).as("不同送礼人同 requestId 不撞")
                .isNotEqualTo(GiftIdemKeys.send(42L, "req-uuid-1"));
    }

    @Test
    void perOrderKeysAreScopedByOrderToken() {
        assertThat(GiftIdemKeys.coin("TOK")).isEqualTo("GIFT_COIN:TOK");
        assertThat(GiftIdemKeys.qris("TOK")).isEqualTo("GIFT_QRIS:TOK");
        assertThat(GiftIdemKeys.refund("TOK")).isEqualTo("GIFT_REFUND:TOK");
    }

    @Test
    void sendKeyFitsTheIdemKeyColumn() {
        // gift_orders.idem_key VARCHAR(96)：requestId ≤ 64（@Size）+ "GIFT:" + Long.MAX_VALUE 位数 + ":"。
        String key = GiftIdemKeys.send(Long.MAX_VALUE, "x".repeat(64));
        assertThat(key.length()).isLessThanOrEqualTo(96);
    }
}
