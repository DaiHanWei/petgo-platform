package com.tailtopia.shop.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.order.domain.ShopOrder;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.4.0 ① Story 1.3 · AC2.2（L0）：电商实付总额 = 币段 + 现金段（与订单总额相等）；未固化拆分时退回总额。 */
class ShopOrderPaidTotalTest {

    private static ShopOrder order(Long coin, Long cash, long total) {
        ShopOrder o = org.springframework.beans.BeanUtils.instantiateClass(ShopOrder.class);
        ReflectionTestUtils.setField(o, "coinAmount", coin);
        ReflectionTestUtils.setField(o, "cashAmount", cash);
        ReflectionTestUtils.setField(o, "totalAmount", total);
        return o;
    }

    @Test
    void mixedIsCoinPlusCashAndEqualsTotal() {
        ShopOrder o = order(50_000L, 150_000L, 200_000);
        assertThat(o.paidTotal()).isEqualTo(200_000).isEqualTo(o.getTotalAmount());
    }

    @Test
    void pureCashOrPureCoinTreatsNullAsZero() {
        assertThat(order(null, 200_000L, 200_000).paidTotal()).isEqualTo(200_000);
        assertThat(order(200_000L, 0L, 200_000).paidTotal()).isEqualTo(200_000);
    }

    @Test
    void unsplitOrderFallsBackToTotal() {
        assertThat(order(null, null, 123_000).paidTotal()).isEqualTo(123_000);
    }
}
