package com.tailtopia.shop.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0（v1.4.2 shop Story 6.2 AC1.3）：电商订单列表游标编码 / 解码。 */
class ShopOrderListCursorTest {

    @Test
    @DisplayName("往返：微秒精度时刻 + id 原样还原（不截到毫秒）")
    void roundTrip() {
        Instant t = Instant.parse("2026-11-05T10:00:00.123456Z");
        ShopOrderListCursor c = ShopOrderListCursor.after(t, 42L);
        ShopOrderListCursor back = ShopOrderListCursor.decode(c.encode());
        assertThat(back.beforeTs()).isEqualTo(t);
        assertThat(back.beforeId()).isEqualTo(42L);
        assertThat(back.isFirst()).isFalse();
        assertThat(c.encode()).doesNotContain(":").doesNotContain("=");
    }

    @Test
    @DisplayName("空串 / null / 坏 Base64 / 段数不对 / 非数字 / id≤0 → 首页")
    void badCursorIsFirstPage() {
        assertThat(ShopOrderListCursor.decode(null).isFirst()).isTrue();
        assertThat(ShopOrderListCursor.decode("").isFirst()).isTrue();
        assertThat(ShopOrderListCursor.decode("%%%not-base64").isFirst()).isTrue();
        assertThat(ShopOrderListCursor.decode(b64("1:2")).isFirst()).isTrue();
        assertThat(ShopOrderListCursor.decode(b64("a:b:c")).isFirst()).isTrue();
        assertThat(ShopOrderListCursor.decode(b64("1:0:0")).isFirst()).isTrue();
        assertThat(ShopOrderListCursor.decode(b64("99999999999999999:0:5")).isFirst()).isTrue();
    }

    @Test
    @DisplayName("时刻越界（早于 2000-01-01 / 晚于 9999-12-31T23:59:59Z / 负秒）→ 首页；边界值本身可用")
    void outOfRangeTimestampIsFirstPage() {
        long min = Instant.parse("2000-01-01T00:00:00Z").getEpochSecond();
        long max = Instant.parse("9999-12-31T23:59:59Z").getEpochSecond();
        assertThat(ShopOrderListCursor.decode(b64((min - 1) + ":0:5")).isFirst()).isTrue();
        assertThat(ShopOrderListCursor.decode(b64(max + ":1:5")).isFirst()).isTrue();
        assertThat(ShopOrderListCursor.decode(b64((max + 1) + ":0:5")).isFirst()).isTrue();
        assertThat(ShopOrderListCursor.decode(b64("-5:0:5")).isFirst()).isTrue();
        ShopOrderListCursor lo = ShopOrderListCursor.decode(b64(min + ":0:5"));
        assertThat(lo.isFirst()).isFalse();
        assertThat(lo.beforeTs()).isEqualTo(Instant.parse("2000-01-01T00:00:00Z"));
        ShopOrderListCursor hi = ShopOrderListCursor.decode(b64(max + ":0:5"));
        assertThat(hi.beforeTs()).isEqualTo(Instant.parse("9999-12-31T23:59:59Z"));
        assertThat(hi.beforeId()).isEqualTo(5L);
    }

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
