package com.tailtopia.shop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.event.ShopOrderShippedEvent;
import com.tailtopia.shop.order.repository.ShipmentRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：v1.4.2 shop Story 5.2 复审 3 —— 转手工运单发货：审计 SHOP_ORDER_SHIPPED + 原为待发货时发布发货事件。 */
class ShopOrderFulfillmentManualShipTest {

    private static final String TOKEN = "tok";
    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    private final ShopOrderRepository orders = mock(ShopOrderRepository.class);
    private final ShipmentRepository shipments = mock(ShipmentRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private ShopOrderFulfillmentService service;
    private ShopOrder order;

    @BeforeEach
    void setUp() {
        service = new ShopOrderFulfillmentService(orders, shipments, events, audit, mock(ShopPaymentAlerts.class));
        order = ShopOrder.place(TOKEN, "TOKO-20261101-ABC123", T0, 1L, 285_000L, 20_000L, 0L,
                new AddressSnapshot("Budi", "+628123456789", "DKI Jakarta", "Jakarta Selatan", "Kebayoran Baru",
                        "Jl. Melawai IV No. 12", "12160"));
        ReflectionTestUtils.setField(order, "id", 5L);
        order.transitionTo(ShopOrderStatus.PENDING_SHIPMENT);
        when(orders.findByPublicToken(TOKEN)).thenReturn(Optional.of(order));
        when(shipments.findByShopOrderIdOrderByIdAsc(5L)).thenReturn(List.of());
        when(shipments.findByCarrierAndTrackingNo(any(), any())).thenReturn(Optional.empty());
        when(shipments.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("原为待发货 → SHIPPED + 审计（承运商、单号，不含收件人）+ 发布 ShopOrderShippedEvent")
    void firstShipPublishesAndAudits() {
        service.shipManualWithAudit(TOKEN, Carrier.JNE, "JN1", 9_000L, 42L);
        assertThat(order.getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);
        verify(audit).record(eq(42L), eq(AuditActions.SHOP_ORDER_SHIPPED), eq("SHOP_ORDER"), eq(TOKEN),
                eq("发货：承运商=JNE 物流单号=JN1"));
        verify(events).publishEvent(new ShopOrderShippedEvent(5L, 1L, TOKEN));
    }

    @Test
    @DisplayName("原已发货（追加包裹）→ 只审计，不再发发货事件")
    void alreadyShippedNoEvent() {
        order.markShipped(T0);
        service.shipManualWithAudit(TOKEN, Carrier.JNE, "JN2", 0L, 42L);
        verify(audit).record(eq(42L), eq(AuditActions.SHOP_ORDER_SHIPPED), eq("SHOP_ORDER"), eq(TOKEN), any());
        verify(events, never()).publishEvent(any(Object.class));
    }
}
