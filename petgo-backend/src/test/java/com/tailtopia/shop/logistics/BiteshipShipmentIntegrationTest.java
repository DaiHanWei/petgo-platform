package com.tailtopia.shop.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.logistics.biteship.BiteshipStubScript;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShipmentStatus;
import com.tailtopia.shop.order.domain.ShippedSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShipmentRepository;
import com.tailtopia.shop.order.service.ShopOrderFulfillmentService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** L1：v1.4.2 shop Story 5.1 —— 下运单 / 失败 / 重试 / 重发 / 取消 / 手工发货拦截（stub 模式）。🔴 云端不跑。 */
@TestPropertySource(properties = {"petgo.shop.sku-cap=500", "petgo.shop.biteship.mode=stub"})
class BiteshipShipmentIntegrationTest extends BiteshipIntegrationSupport {

    @Autowired
    private BiteshipShipmentService waybills;
    @Autowired
    private BiteshipTrackingService tracking;
    @Autowired
    private BiteshipStubScript stub;
    @Autowired
    private ShipmentRepository shipments;
    @Autowired
    private ShopOrderFulfillmentService fulfillment;

    private Shipment only(long orderId) {
        List<Shipment> list = shipments.findByShopOrderIdOrderByIdAsc(orderId);
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    @Test
    @DisplayName("已付款订单下运单 → 运单 CREATED、订单仍 PENDING_SHIPMENT")
    void createWaybill() {
        ShopOrder o = paidBiteshipOrder(seedUser());
        assertThat(waybills.createWaybill(o.getId(), ACTOR).created()).isTrue();
        Shipment s = only(o.getId());
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(s.getBiteshipOrderId()).startsWith("stub_");
        assertThat(reload(o.getPublicToken()).getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
    }

    @Test
    @DisplayName("failNextCreate → 失败标记；重试成功清标记")
    void failureThenRetry() {
        ShopOrder o = paidBiteshipOrder(seedUser());
        stub.failNextCreate("40000001");
        assertThat(waybills.createWaybill(o.getId(), ACTOR).failReason()).isEqualTo("REJECTED:40000001");
        assertThat(reload(o.getPublicToken()).getWaybillFailedAt()).isNotNull();
        assertThat(waybills.retryWaybill(o.getId(), ACTOR).created()).isTrue();
        ShopOrder after = reload(o.getPublicToken());
        assertThat(after.getWaybillFailedAt()).isNull();
        assertThat(after.getWaybillAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("reshipForCourierFault：旧运单 superseded、新运单 CREATED")
    void reship() {
        ShopOrder o = paidBiteshipOrder(seedUser());
        waybills.createWaybill(o.getId(), ACTOR);
        Shipment first = only(o.getId());
        stub.setStatus(first.getBiteshipOrderId(), "picked");
        tracking.refresh(first.getId());
        assertThat(reload(o.getPublicToken()).getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);

        assertThat(waybills.reshipForCourierFault(o.getId(), ACTOR).created()).isTrue();
        List<Shipment> all = shipments.findByShopOrderIdOrderByIdAsc(o.getId());
        assertThat(all).hasSize(2);
        assertThat(all.get(0).getSupersededAt()).isNotNull();
        assertThat(all.get(1).getStatus()).isEqualTo(ShipmentStatus.CREATED);
    }

    @Test
    @DisplayName("cancelWaybill → 本地 CANCELLED")
    void cancel() {
        ShopOrder o = paidBiteshipOrder(seedUser());
        waybills.createWaybill(o.getId(), ACTOR);
        waybills.cancelWaybill(o.getId(), "others", ACTOR);
        assertThat(only(o.getId()).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("手工 ship 遇活动 Biteship 运单 409；markShippedWithoutWaybill → SHIPPED + MANUAL，之后 picked 不改订单")
    void manualShipBlockedAndMarkShipped() {
        ShopOrder o = paidBiteshipOrder(seedUser());
        waybills.createWaybill(o.getId(), ACTOR);
        assertThatThrownBy(() -> fulfillment.ship(o.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 1L))
                .isInstanceOf(AppException.class).hasMessageContaining("Biteship");

        fulfillment.markShippedWithoutWaybill(o.getPublicToken(), ACTOR);
        ShopOrder shipped = reload(o.getPublicToken());
        assertThat(shipped.getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);
        assertThat(shipped.getShippedSource()).isEqualTo(ShippedSource.MANUAL);

        Shipment s = only(o.getId());
        stub.setStatus(s.getBiteshipOrderId(), "picked");
        tracking.refresh(s.getId());
        ShopOrder after = reload(o.getPublicToken());
        assertThat(after.getShippedSource()).isEqualTo(ShippedSource.MANUAL);
        assertThat(after.getShippedAt()).isEqualTo(shipped.getShippedAt());
        assertThat(only(o.getId()).getStatus()).isEqualTo(ShipmentStatus.SHIPPED);
    }

    @Test
    @DisplayName("AC11.2：取消 Biteship 运单后转手工发货，后台标记送达只处理活动运单（已取消运单保持 CANCELLED）")
    void adminDeliverSkipsCancelled() {
        ShopOrder o = paidBiteshipOrder(seedUser());
        waybills.createWaybill(o.getId(), ACTOR);
        waybills.cancelWaybill(o.getId(), "change_courier", ACTOR);
        fulfillment.ship(o.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 1L);
        fulfillment.markDeliveredByAdmin(o.getPublicToken());

        List<Shipment> all = shipments.findByShopOrderIdOrderByIdAsc(o.getId());
        assertThat(all.get(0).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(all.get(1).getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(reload(o.getPublicToken()).getStatus()).isEqualTo(ShopOrderStatus.DELIVERED);
    }
}
