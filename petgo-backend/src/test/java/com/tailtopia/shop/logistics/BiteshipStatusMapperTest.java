package com.tailtopia.shop.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.logistics.BiteshipStatusMapper.Mapping;
import com.tailtopia.shop.logistics.BiteshipStatusMapper.OrderAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** L0：v1.4.2 shop Story 5.1 AC8.1 —— Biteship 15 个状态 + 未知值穷举（D-12 映射表）。 */
class BiteshipStatusMapperTest {

    @ParameterizedTest(name = "{0} → {1} / {2}")
    @CsvSource(nullValues = "NULL", value = {
        "confirmed,awaiting_pickup,NONE",
        "scheduled,awaiting_pickup,NONE",
        "allocated,awaiting_pickup,NONE",
        "picking_up,awaiting_pickup,NONE",
        "picked,picked,SHIP",
        "in_transit,in_transit,SHIP",
        "on_hold,in_transit,SHIP",
        "dropping_off,out_for_delivery,SHIP",
        "delivered,delivered,DELIVER",
        "courier_not_found,NULL,FLAG_PICKUP_FAILED",
        "return_in_transit,NULL,FLAG_RETURNED",
        "returned,NULL,FLAG_RETURNED",
        "rejected,NULL,ALERT_OPS",
        "disposed,NULL,ALERT_OPS",
        "cancelled,NULL,ALERT_OPS",
    })
    @DisplayName("15 个已知状态逐一映射")
    void knownStatuses(String status, String userKey, OrderAction action) {
        Mapping m = BiteshipStatusMapper.map(status);
        assertThat(m.userStatusKey()).isEqualTo(userKey);
        assertThat(m.action()).isEqualTo(action);
        assertThat(m.known()).isTrue();
    }

    @ParameterizedTest(name = "未知值 [{0}] → ALERT_OPS、用户状态不变")
    @CsvSource(nullValues = "NULL", value = {"''", "foo", "NULL"})
    void unknownStatuses(String status) {
        Mapping m = BiteshipStatusMapper.map(status);
        assertThat(m.userStatusKey()).isNull();
        assertThat(m.action()).isEqualTo(OrderAction.ALERT_OPS);
        assertThat(m.known()).isFalse();
    }

    @Test
    @DisplayName("大小写 / 首尾空格不敏感")
    void caseInsensitive() {
        assertThat(BiteshipStatusMapper.map(" Picked ").action()).isEqualTo(OrderAction.SHIP);
    }
}
