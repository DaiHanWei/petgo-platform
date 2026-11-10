package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** L0（V1.4.2 shop-admin Story 5.1 · AC1 · AD-9）：队列来源一个函数判定，优先级 退回 > 丢件破损 > 揽收失败 > 运单失败 > 缺货。 */
class ExceptionSourceTest {

    @ParameterizedTest(name = "{0} shortage={1} waybillFailed={2} le={3} comp={4} → {5}")
    @CsvSource(nullValues = "-", value = {
            // 缺货：只认待发货
            "PENDING_SHIPMENT, true,  false, -,             false, OUT_OF_STOCK",
            "SHIPPED,          true,  false, -,             false, -",
            // 运单失败：待发货 / 已发货；优先于缺货
            "PENDING_SHIPMENT, true,  true,  -,             false, WAYBILL_FAILED",
            "SHIPPED,          false, true,  -,             false, WAYBILL_FAILED",
            "DELIVERED,        false, true,  -,             false, -",
            // 揽收失败：待发货 / 已发货；优先于运单失败
            "PENDING_SHIPMENT, false, true,  PICKUP_FAILED, false, PICKUP_FAILED",
            "SHIPPED,          false, false, PICKUP_FAILED, false, PICKUP_FAILED",
            // 退回：待发货（只展示）/ 已发货 / 已签收（O-29 只开重发）；重发失败的已发货单来源为退回
            "SHIPPED,          false, true,  RETURNED,      false, RETURNED",
            "PENDING_SHIPMENT, false, false, RETURNED,      false, RETURNED",
            "DELIVERED,        false, false, RETURNED,      false, RETURNED",
            "COMPLETED,        false, false, RETURNED,      false, -",
            // 丢件破损：已发货，或已签收 / 已完成且尚无赔付单
            "SHIPPED,          false, false, LOST,          false, LOST_DAMAGED",
            "DELIVERED,        false, false, DAMAGED,       false, LOST_DAMAGED",
            "COMPLETED,        false, false, LOST,          false, LOST_DAMAGED",
            "COMPLETED,        false, false, LOST,          true,  -",
            // 停滞不在本队列；取消 / 退款中 / 已退款一律出队
            "SHIPPED,          false, false, STALLED,       false, -",
            "CANCELLED,        true,  true,  -,             false, -",
            "REFUNDING,        false, true,  RETURNED,      false, -",
            "REFUNDED,         false, false, LOST,          false, -",
    })
    void table(ShopOrderStatus status, boolean shortage, boolean waybillFailed, LogisticsException le,
            boolean hasCompensation, ExceptionSource expected) {
        assertThat(ExceptionSource.of(new ExceptionSource.Facts(status, shortage, waybillFailed, le, hasCompensation)))
                .isEqualTo(java.util.Optional.ofNullable(expected));
    }
}
