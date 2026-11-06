package com.tailtopia.shop.order.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.pricing.ShippingDiscountSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：v1.4.2 shop Story 5.1 AC13 —— 订单详情 {@code logistics} 出参与 {@code packages} 追加字段。 */
class ShopOrderDetailViewLogisticsTest {

    private static final Instant T = Instant.parse("2026-11-05T10:00:00Z");

    private static ShopOrder order() {
        ShopOrder o = ShopOrder.placeCheckout(new ShopOrder.PlaceSnapshot("ord", "TOKO-20261105-ABC123", T, 9L,
                new AddressSnapshot("Budi", "+628123", "DKI", "Jaksel", "Kebayoran", "Jl. 1", "12160"), null,
                CheckoutSource.CART, 100_000L, 0L, 10_000L, 0L, ShippingDiscountSource.NONE, 0L, false, 110_000L,
                null, null, "jne", "reg", "JNE", "Reguler", "2 - 3", 1L, false));
        ReflectionTestUtils.setField(o, "id", 1L);
        return o;
    }

    private static Shipment bs(long id, String boid) {
        Shipment s = Shipment.biteship(1L, "JNE", "jne", "reg", boid, "R" + id, "WB" + id, null, 10_000L, 0L, T);
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    private static ShopOrderDetailView view(List<Shipment> shipments) {
        return ShopOrderDetailView.of(order(), List.of(), shipments, Map.of(), null, T);
    }

    @Test
    @DisplayName("无运单 → logistics null")
    void noShipment() {
        assertThat(view(List.of()).logistics()).isNull();
    }

    @Test
    @DisplayName("手工运单 → statusKey null、trackingUrl 为承运商官网、courierName 为展示名")
    void manual() {
        Shipment m = Shipment.ship(1L, Carrier.SICEPAT, "SC1", 0L);
        ReflectionTestUtils.setField(m, "id", 5L);
        ShopOrderDetailView.Logistics l = view(List.of(m)).logistics();
        assertThat(l.provider()).isEqualTo("MANUAL");
        assertThat(l.courierName()).isEqualTo("SiCepat");
        assertThat(l.courierServiceName()).isNull();
        assertThat(l.waybillId()).isEqualTo("SC1");
        assertThat(l.trackingUrl()).isEqualTo(Carrier.SICEPAT.trackingUrl());
        assertThat(l.statusKey()).isNull();
    }

    @Test
    @DisplayName("Biteship 取最新活动运单（superseded 不选），快递名 / 服务名取订单快照")
    void biteshipLatestActive() {
        Shipment old = bs(1L, "bs_1");
        old.markPicked(T);
        old.supersede(T);
        Shipment cur = bs(2L, "bs_2");
        cur.updateTrackingUrl("https://track.example/WB2");
        ShopOrderDetailView v = view(List.of(old, cur));
        ShopOrderDetailView.Logistics l = v.logistics();
        assertThat(l.provider()).isEqualTo("BITESHIP");
        assertThat(l.courierName()).isEqualTo("JNE");
        assertThat(l.courierServiceName()).isEqualTo("Reguler");
        assertThat(l.waybillId()).isEqualTo("WB2");
        assertThat(l.statusKey()).isEqualTo("awaiting_pickup");
        assertThat(l.trackingUrl()).isEqualTo("https://track.example/WB2");
        assertThat(l.exception()).isNull();
        assertThat(l.exceptionType()).isNull();

        assertThat(v.packages()).hasSize(2);
        assertThat(v.packages().get(0).superseded()).isTrue();
        assertThat(v.packages().get(0).provider()).isEqualTo("BITESHIP");
        assertThat(v.packages().get(1).superseded()).isFalse();
        assertThat(v.packages().get(1).carrier()).isEqualTo("JNE");
    }

    @Test
    @DisplayName("运单全被取消 → logistics null，packages 仍列出")
    void allCancelled() {
        Shipment s = bs(1L, "bs_1");
        s.markCancelled(T);
        ShopOrderDetailView v = view(List.of(s));
        assertThat(v.logistics()).isNull();
        assertThat(v.packages()).hasSize(1);
        assertThat(v.packages().get(0).status()).isEqualTo("CANCELLED");
    }

    // ---------- v1.4.2 shop Story 5.2 AC5：exception / exceptionType ----------

    private static ShopOrderDetailView.Logistics logisticsOf(ShopOrder o, List<Shipment> shipments) {
        return ShopOrderDetailView.of(o, List.of(), shipments, Map.of(), null, T).logistics();
    }

    private static ShopOrder shippedOrder() {
        ShopOrder o = order();
        o.transitionTo(com.tailtopia.shop.order.domain.ShopOrderStatus.PENDING_SHIPMENT);
        o.markShipped(T);
        return o;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = LogisticsException.class,
            names = {"RETURNED", "LOST", "DAMAGED"})
    @DisplayName("已确认的退回 / 丢件 / 破损 + SHIPPED → DELIVERY_ISSUE + 类型；statusKey 照常下发")
    void deliveryIssue(LogisticsException type) {
        ShopOrder o = shippedOrder();
        o.flagLogisticsException(type, T);
        o.confirmLogisticsException(type, true, T);
        Shipment s = bs(1L, "bs_1");
        s.applyProviderStatus("return_in_transit", T.plusSeconds(5), "in_transit");
        ShopOrderDetailView.Logistics l = logisticsOf(o, List.of(s));
        assertThat(l.exception()).isEqualTo("DELIVERY_ISSUE");
        assertThat(l.exceptionType()).isEqualTo(type.name());
        assertThat(l.statusKey()).isEqualTo("in_transit");
    }

    @Test
    @DisplayName("PICKUP_FAILED / STALLED / 未确认的 RETURNED → 两者都为 null，statusKey 不受影响")
    void hiddenCases() {
        for (LogisticsException type : List.of(LogisticsException.PICKUP_FAILED, LogisticsException.STALLED,
                LogisticsException.RETURNED)) {
            ShopOrder o = shippedOrder();
            o.flagLogisticsException(type, T);
            ShopOrderDetailView.Logistics l = logisticsOf(o, List.of(bs(1L, "bs_1")));
            assertThat(l.exception()).as(type.name()).isNull();
            assertThat(l.exceptionType()).as(type.name()).isNull();
            assertThat(l.statusKey()).isEqualTo("awaiting_pickup");
        }
    }

    @Test
    @DisplayName("订单已完成 → 不再显示配送异常；手工运单同口径显示")
    void completedHiddenManualShown() {
        ShopOrder done = shippedOrder();
        done.markDelivered(T, com.tailtopia.shop.order.domain.DeliverySource.ADMIN_MARK);
        done.confirmLogisticsException(LogisticsException.DAMAGED, true, T);
        done.markCompleted(T.plusSeconds(1), com.tailtopia.shop.order.domain.CompletionSource.USER_CONFIRM);
        assertThat(logisticsOf(done, List.of(bs(1L, "bs_1"))).exception()).isNull();

        ShopOrder manual = shippedOrder();
        manual.confirmLogisticsException(LogisticsException.LOST, true, T);
        Shipment m = Shipment.ship(1L, Carrier.JNE, "JN1", 0L);
        ReflectionTestUtils.setField(m, "id", 3L);
        ShopOrderDetailView.Logistics l = logisticsOf(manual, List.of(m));
        assertThat(l.exception()).isEqualTo("DELIVERY_ISSUE");
        assertThat(l.exceptionType()).isEqualTo("LOST");
        assertThat(l.statusKey()).isNull();
    }

    @Test
    @DisplayName("AC5.3：无运单但带异常 → logistics 仍为 null，不另造对象")
    void noShipmentWithException() {
        ShopOrder o = shippedOrder();
        o.confirmLogisticsException(LogisticsException.LOST, true, T);
        assertThat(logisticsOf(o, List.of())).isNull();
    }
}
