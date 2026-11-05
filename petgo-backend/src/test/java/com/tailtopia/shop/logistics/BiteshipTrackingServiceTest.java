package com.tailtopia.shop.logistics;

import static com.tailtopia.shop.logistics.LogisticsFixtures.NOW;
import static com.tailtopia.shop.logistics.LogisticsFixtures.ORDER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.logistics.biteship.BiteshipClient;
import com.tailtopia.shared.logistics.biteship.BiteshipException;
import com.tailtopia.shared.logistics.biteship.BiteshipProperties;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipOrderSnapshot;
import com.tailtopia.shop.logistics.domain.BiteshipWebhookEvent;
import com.tailtopia.shop.logistics.repository.BiteshipWebhookEventRepository;
import com.tailtopia.shop.order.domain.DeliverySource;
import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShipmentStatus;
import com.tailtopia.shop.order.domain.ShippedSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.event.ShopOrderShippedEvent;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.repository.ShipmentRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.ShopPaymentAlerts;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/** L0：v1.4.2 shop Story 5.1 AC7 / AC8 —— 回查推进、伪造载荷不改状态（P-12）、幂等、失败计数与告警。 */
class BiteshipTrackingServiceTest {

    private static final long EVENT_ID = 7L;

    private final BiteshipWebhookEventRepository inbox = mock(BiteshipWebhookEventRepository.class);
    private final ShipmentRepository shipments = mock(ShipmentRepository.class);
    private final ShopOrderRepository orders = mock(ShopOrderRepository.class);
    private final BiteshipClient client = mock(BiteshipClient.class);
    private final BiteshipProperties props = new BiteshipProperties();
    private final ShopPaymentAlerts alerts = mock(ShopPaymentAlerts.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager txm = mock(PlatformTransactionManager.class);

    private BiteshipTrackingService service;
    private ShopOrder order;
    private Shipment shipment;
    private BiteshipWebhookEvent row;

    @BeforeEach
    void setUp() {
        service = new BiteshipTrackingService(inbox, shipments, orders, client, props, alerts, events, txm,
                Clock.fixed(NOW.plusSeconds(7200), ZoneOffset.UTC));
        order = LogisticsFixtures.paidOrder(false);
        shipment = LogisticsFixtures.biteshipShipment(1L, "bs_1");
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(shipments.findById(1L)).thenAnswer(inv -> Optional.of(shipment));
        when(shipments.findByBiteshipOrderId("bs_1")).thenAnswer(inv -> Optional.of(shipment));
        when(shipments.findByShopOrderIdOrderByIdAsc(ORDER_ID)).thenAnswer(inv -> List.of(shipment));
        row = BiteshipWebhookEvent.receive("bs_1", "order.status", Map.of("status", "delivered"), NOW);
        ReflectionTestUtils.setField(row, "id", EVENT_ID);
        when(inbox.findById(EVENT_ID)).thenAnswer(inv -> Optional.of(row));
    }

    private static BiteshipOrderSnapshot snap(String status, Instant updatedAt) {
        return new BiteshipOrderSnapshot("bs_1", status, "WB1", null, "jne", "reg", 10_000L, null, updatedAt);
    }

    private void remote(String status, Instant at) {
        when(client.getOrder("bs_1")).thenReturn(snap(status, at));
    }

    @Test
    @DisplayName("🔴 P-12：载荷写 delivered、回查返回 confirmed → 订单与运单都不变，收件行置已处理")
    void forgedPayloadIgnored() {
        remote("confirmed", NOW.plusSeconds(60));
        service.process(EVENT_ID);
        assertThat(order.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        verify(shipments, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
        assertThat(row.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("picked → 订单 SHIPPED + BITESHIP_PICKED + 发 ShopOrderShippedEvent；运单 SHIPPED、用户侧 picked")
    void pickedShipsOrder() {
        Instant at = NOW.plusSeconds(3600);
        remote("picked", at);
        service.process(EVENT_ID);
        assertThat(order.getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);
        assertThat(order.getShippedSource()).isEqualTo(ShippedSource.BITESHIP_PICKED);
        assertThat(order.getShippedAt()).isEqualTo(at);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.SHIPPED);
        assertThat(shipment.getUserStatusKey()).isEqualTo("picked");
        verify(events).publishEvent(new ShopOrderShippedEvent(ORDER_ID, LogisticsFixtures.USER,
                LogisticsFixtures.TOKEN));
        assertThat(row.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("delivered → 订单 DELIVERED + BITESHIP（签收时刻 = 运单送达时刻）")
    void deliveredDeliversOrder() {
        Instant at = NOW.plusSeconds(86_400);
        remote("delivered", at);
        service.process(EVENT_ID);
        assertThat(order.getStatus()).isEqualTo(ShopOrderStatus.DELIVERED);
        assertThat(order.getDeliverySource()).isEqualTo(DeliverySource.BITESHIP);
        assertThat(order.getDeliveredAt()).isEqualTo(at);
        assertThat(order.getShippedSource()).isEqualTo(ShippedSource.BITESHIP_PICKED);
        assertThat(shipment.getUserStatusKey()).isEqualTo("delivered");
    }

    @Test
    @DisplayName("同一状态重复到达：第二次不写库、不发事件")
    void idempotent() {
        assertThat(service.applySnapshot(1L, snap("picked", NOW.plusSeconds(60)))).isTrue();
        assertThat(service.applySnapshot(1L, snap("picked", NOW.plusSeconds(120)))).isFalse();
        verify(shipments, times(1)).save(any());
        verify(events, times(1)).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("旧 updatedAt 的快照不回退状态")
    void staleSnapshotIgnored() {
        service.applySnapshot(1L, snap("in_transit", NOW.plusSeconds(600)));
        assertThat(service.applySnapshot(1L, snap("picked", NOW.plusSeconds(300)))).isFalse();
        assertThat(shipment.getProviderStatus()).isEqualTo("in_transit");
        assertThat(shipment.getUserStatusKey()).isEqualTo("in_transit");
    }

    @Test
    @DisplayName("superseded 运单：只记 provider_status，不推进订单")
    void supersededDoesNotAdvance() {
        shipment.supersede(NOW);
        service.applySnapshot(1L, snap("delivered", NOW.plusSeconds(60)));
        assertThat(shipment.getProviderStatus()).isEqualTo("delivered");
        assertThat(order.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("courier_not_found → PICKUP_FAILED；returned → RETURNED（覆盖 STALLED）；已有 LOST 不覆盖")
    void exceptionFlags() {
        service.applySnapshot(1L, snap("courier_not_found", NOW.plusSeconds(60)));
        assertThat(order.getLogisticsException()).isEqualTo(LogisticsException.PICKUP_FAILED);
        assertThat(order.isLogisticsExceptionConfirmed()).isFalse();
        assertThat(shipment.getUserStatusKey()).as("映射为 null 不改用户侧状态").isEqualTo("awaiting_pickup");

        ReflectionTestUtils.setField(order, "logisticsException", LogisticsException.STALLED);
        service.applySnapshot(1L, snap("returned", NOW.plusSeconds(120)));
        assertThat(order.getLogisticsException()).isEqualTo(LogisticsException.RETURNED);

        ReflectionTestUtils.setField(order, "logisticsException", LogisticsException.LOST);
        service.applySnapshot(1L, snap("return_in_transit", NOW.plusSeconds(180)));
        assertThat(order.getLogisticsException()).isEqualTo(LogisticsException.LOST);
        verify(alerts, times(2)).alert(anyString());
    }

    @Test
    @DisplayName("🔴 复审：带 RETURNED 异常时收到 delivered：不推进订单、不写 provider_status / user_status_key，告警；"
            + "下一次回查仍会处理，清标记后正常签收")
    void deliveredBlockedByReturned() {
        order.markShipped(NOW);
        shipment.markPicked(NOW);
        service.applySnapshot(1L, snap("in_transit", NOW.plusSeconds(30)));
        ReflectionTestUtils.setField(order, "logisticsException", LogisticsException.RETURNED);

        service.applySnapshot(1L, snap("delivered", NOW.plusSeconds(60)));
        assertThat(order.getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);
        assertThat(shipment.getStatus()).isNotEqualTo(ShipmentStatus.DELIVERED);
        assertThat(shipment.getProviderStatus()).isEqualTo("in_transit");
        assertThat(shipment.getUserStatusKey()).as("App 不得显示已送达").isEqualTo("in_transit");
        verify(alerts).alert(anyString());

        assertThat(service.applySnapshot(1L, snap("delivered", NOW.plusSeconds(90))))
                .as("下一次回查仍判为有变化、重新判定").isTrue();
        verify(alerts, times(2)).alert(anyString());

        ReflectionTestUtils.setField(order, "logisticsException", null);     // 5.2 清标记后
        service.applySnapshot(1L, snap("delivered", NOW.plusSeconds(120)));
        assertThat(order.getStatus()).isEqualTo(ShopOrderStatus.DELIVERED);
        assertThat(shipment.getUserStatusKey()).isEqualTo("delivered");
    }

    @Test
    @DisplayName("🔴 复审：本地已发起取消后 Biteship 先推 cancelled → 直接落 CANCELLED、不告警；订单退款中同样")
    void expectedCancelNoAlert() {
        shipment.markCancelRequested(NOW);
        service.applySnapshot(1L, snap("cancelled", NOW.plusSeconds(60)));
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        verify(alerts, never()).alert(anyString());

        shipment = LogisticsFixtures.biteshipShipment(1L, "bs_1");
        order.markRefunding(CancelReason.OUT_OF_STOCK, NOW);
        service.applySnapshot(1L, snap("cancelled", NOW.plusSeconds(60)));
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        verify(alerts, never()).alert(anyString());
    }

    @Test
    @DisplayName("非预期 cancelled（订单仍待发货、本地未发起取消）→ 告警，运单不改本地状态")
    void unexpectedCancelAlerts() {
        service.applySnapshot(1L, snap("cancelled", NOW.plusSeconds(60)));
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        verify(alerts).alert(org.mockito.ArgumentMatchers.contains("cancelled"));
    }

    @Test
    @DisplayName("rejected → 告警运营，订单不动")
    void rejectedAlerts() {
        service.applySnapshot(1L, snap("rejected", NOW.plusSeconds(60)));
        assertThat(order.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
        assertThat(shipment.getProviderStatus()).isEqualTo("rejected");
        verify(alerts).alert(org.mockito.ArgumentMatchers.contains("rejected"));
    }

    @Test
    @DisplayName("已取消（退款中）订单的运单被揽收 → 告警，订单不变")
    void cancelledOrderPicked() {
        order.markRefunding(CancelReason.OUT_OF_STOCK, NOW);
        service.applySnapshot(1L, snap("picked", NOW.plusSeconds(60)));
        assertThat(order.getStatus()).isEqualTo(ShopOrderStatus.REFUNDING);
        verify(alerts).alert(org.mockito.ArgumentMatchers.contains("已取消订单"));
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("getOrder 抛错 → attempts + 1、留待重试")
    void getOrderFailureCounts() {
        when(client.getOrder("bs_1")).thenThrow(new BiteshipException(BiteshipException.Kind.NETWORK, null, null));
        service.process(EVENT_ID);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isEqualTo("BiteshipException");
        assertThat(row.getProcessedAt()).isNull();
        assertThat(row.getAlertedAt()).isNull();
    }

    @Test
    @DisplayName("第 10 次非 UNKNOWN_ORDER 失败 → alerted_at + 告警，不置 processed_at；之后不再处理")
    void tenthFailureAlerts() {
        when(client.getOrder("bs_1")).thenThrow(new BiteshipException(BiteshipException.Kind.SERVER, 500, null));
        for (int i = 0; i < 12; i++) {
            service.process(EVENT_ID);
        }
        assertThat(row.getAttempts()).isEqualTo(10);
        assertThat(row.getAlertedAt()).isNotNull();
        assertThat(row.getProcessedAt()).isNull();
        verify(alerts, times(1)).alert(anyString());
        verify(client, times(10)).getOrder("bs_1");
    }

    @Test
    @DisplayName("UNKNOWN_ORDER 第 10 次 → 置 processed_at，不告警")
    void unknownOrderGivesUp() {
        when(shipments.findByBiteshipOrderId("bs_1")).thenReturn(Optional.empty());
        for (int i = 0; i < 10; i++) {
            service.process(EVENT_ID);
        }
        assertThat(row.getAttempts()).isEqualTo(10);
        assertThat(row.getLastError()).isEqualTo("UNKNOWN_ORDER");
        assertThat(row.getProcessedAt()).isNotNull();
        assertThat(row.getAlertedAt()).isNull();
        verify(alerts, never()).alert(anyString());
        verify(client, never()).getOrder(anyString());
    }
}
