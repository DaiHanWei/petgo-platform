package com.tailtopia.shop.order.service;

import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;

/** v1.4.2 shop Story 6.2 L0 夹具：直接造任意状态 / 订单窗的订单（不碰 DB）。 */
final class ShopOrderListFixtures {

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private ShopOrderListFixtures() {
    }

    static ShopOrder order(long id, ShopOrderStatus status, Instant expiresAt) {
        ShopOrder o = ShopOrder.place("tok" + id, "TOKO-20261105-" + id, NOW.minusSeconds(3600), 7L, 200_000L,
                20_000L, 0L, new AddressSnapshot("Budi", "+628123", "DKI", "Jaksel", "Kebayoran", "Jl. 1", "12160"));
        ReflectionTestUtils.setField(o, "id", id);
        ReflectionTestUtils.setField(o, "status", status);
        ReflectionTestUtils.setField(o, "expiresAt", expiresAt);
        ReflectionTestUtils.setField(o, "createdAt", NOW.minusSeconds(id));
        return o;
    }
}
