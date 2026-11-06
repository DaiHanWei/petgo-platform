package com.tailtopia.shop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.CompletionSource;
import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShipmentRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：v1.4.2 shop Story 5.2 AC6 —— 物流异常订单确认收货：照常完成 + 清标记 + 运营提醒，claim_pending 保留。 */
class ShopOrderFulfillmentConfirmTest {

    private static final long USER = 1L;
    private static final String TOKEN = "tok";
    private static final String DISPLAY_NO = "TOKO-20261105-ABC123";
    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    private final ShopOrderRepository orders = mock(ShopOrderRepository.class);
    private final ShipmentRepository shipments = mock(ShipmentRepository.class);
    private final ShopPaymentAlerts alerts = mock(ShopPaymentAlerts.class);
    private ShopOrderFulfillmentService service;
    private ShopOrder order;

    @BeforeEach
    void setUp() {
        service = new ShopOrderFulfillmentService(orders, shipments, mock(ApplicationEventPublisher.class),
                mock(AdminAuditService.class), alerts);
        order = ShopOrder.place(TOKEN, DISPLAY_NO, T0, USER, 285_000L, 20_000L, 0L,
                new AddressSnapshot("Budi", "+628123456789", "DKI Jakarta", "Jakarta Selatan", "Kebayoran Baru",
                        "Jl. Melawai IV No. 12", "12160"));
        ReflectionTestUtils.setField(order, "id", 5L);
        order.transitionTo(ShopOrderStatus.PENDING_SHIPMENT);
        order.markShipped(T0);
        when(orders.findByPublicTokenAndUserId(TOKEN, USER)).thenReturn(Optional.of(order));
        when(shipments.findByShopOrderIdOrderByIdAsc(5L)).thenReturn(List.of());
    }

    @ParameterizedTest
    @EnumSource(LogisticsException.class)
    @DisplayName("任一异常下确认收货不阻拦 → COMPLETED + 清标记 + 飞书提醒（展示号）；claim_pending 保留")
    void confirmClearsException(LogisticsException type) {
        if (type.userVisibleWhenConfirmed()) {
            order.flagLogisticsException(type, T0);
            order.confirmLogisticsException(type, true, T0);
        } else {
            order.flagLogisticsException(type, T0);
            order.setClaimPending(true);
        }
        ShopOrder done = service.confirmReceipt(USER, TOKEN);
        assertThat(done.getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
        assertThat(done.getCompletionSource()).isEqualTo(CompletionSource.USER_CONFIRM);
        assertThat(done.getLogisticsException()).isNull();
        assertThat(done.isLogisticsExceptionConfirmed()).isFalse();
        assertThat(done.getTimerResumedAt()).isNotNull();
        assertThat(done.isClaimPending()).isTrue();
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(alerts).alert(org.mockito.ArgumentMatchers.eq(com.tailtopia.admin.alert.OpsChannel.OPS), text.capture());
        assertThat(text.getValue()).contains(DISPLAY_NO).contains(type.name()).doesNotContain("Budi")
                .doesNotContain(TOKEN + " ");
        verify(orders).save(order);
    }

    @Test
    @DisplayName("无异常订单：行为与原来一致（完成、不提醒、不写 timer_resumed_at）")
    void confirmWithoutException() {
        ShopOrder done = service.confirmReceipt(USER, TOKEN);
        assertThat(done.getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
        assertThat(done.getDeliveredAt()).isNotNull();
        assertThat(done.getTimerResumedAt()).isNull();
        verify(alerts, never()).alert(org.mockito.ArgumentMatchers.eq(com.tailtopia.admin.alert.OpsChannel.OPS), anyString());
    }

    @Test
    @DisplayName("AC6.2：带 PICKUP_FAILED 的待发货订单仍不能确认收货（现状不改）")
    void pendingShipmentStillRejected() {
        ShopOrder pending = ShopOrder.place("tok2", DISPLAY_NO, T0, USER, 285_000L, 20_000L, 0L,
                new AddressSnapshot("Budi", "+628123456789", "DKI Jakarta", "Jakarta Selatan", "Kebayoran Baru",
                        "Jl. Melawai IV No. 12", "12160"));
        pending.transitionTo(ShopOrderStatus.PENDING_SHIPMENT);
        pending.flagLogisticsException(LogisticsException.PICKUP_FAILED, T0);
        when(orders.findByPublicTokenAndUserId("tok2", USER)).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> service.confirmReceipt(USER, "tok2")).isInstanceOf(AppException.class);
        assertThat(pending.getLogisticsException()).isEqualTo(LogisticsException.PICKUP_FAILED);
        verify(alerts, never()).alert(org.mockito.ArgumentMatchers.eq(com.tailtopia.admin.alert.OpsChannel.OPS), anyString());
    }

    @Test
    @DisplayName("已完成订单重复确认：幂等，不再提醒")
    void idempotentCompleted() {
        service.confirmReceipt(USER, TOKEN);
        service.confirmReceipt(USER, TOKEN);
        verify(alerts, never()).alert(org.mockito.ArgumentMatchers.eq(com.tailtopia.admin.alert.OpsChannel.OPS), anyString());
    }
}
