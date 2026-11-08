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
    private final com.tailtopia.shop.order.repository.ShopOrderLineRepository lines =
            mock(com.tailtopia.shop.order.repository.ShopOrderLineRepository.class);
    private final com.tailtopia.admin.alert.OpsAlertService ops = mock(com.tailtopia.admin.alert.OpsAlertService.class);
    private ShopOrderFulfillmentService service;
    private ShopOrder order;

    @BeforeEach
    void setUp() {
        service = new ShopOrderFulfillmentService(orders, shipments, mock(ApplicationEventPublisher.class),
                mock(AdminAuditService.class), alerts, lines, ops);
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
        // V1.4.2 shop-admin 6.2 · AC9：改走 OpsAlertService（OPS，去重键 receipt-cleared:{token}）
        verify(ops).send(org.mockito.ArgumentMatchers.eq(com.tailtopia.admin.alert.OpsChannel.OPS),
                org.mockito.ArgumentMatchers.eq("receipt-cleared:" + TOKEN), text.capture());
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
        verify(ops, never()).send(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
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
        verify(ops, never()).send(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
    }

    @Test
    @DisplayName("已完成订单重复确认：幂等，不再提醒")
    void idempotentCompleted() {
        service.confirmReceipt(USER, TOKEN);
        service.confirmReceipt(USER, TOKEN);
        verify(ops, never()).send(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
    }

    // ---------- V1.4.2 shop-admin Story 6.2 · O-16 / AC9 ----------

    private void inboundLine(int inboundQty) {
        var line = com.tailtopia.shop.order.domain.ShopOrderLine.of(5L, 9L, "Royal Canin", "2kg", 100_000L, 2,
                com.tailtopia.shop.domain.ReturnPolicy.RETURNABLE);
        if (inboundQty > 0) {
            line.addReturnInboundQty(inboundQty);
        }
        when(lines.findByOrderIdOrderByIdAsc(5L)).thenReturn(List.of(line));
    }

    @Test
    @DisplayName("O-16：已开始按行确认退回入库 → 409 return-inbound-confirmed，订单不动")
    void confirmAfterInboundStartedIs409() {
        order.flagLogisticsException(LogisticsException.RETURNED, T0);
        order.decideReturnCause(com.tailtopia.shop.logistics.ReturnCause.USER_FAULT);
        inboundLine(1);
        assertThatThrownBy(() -> service.confirmReceipt(USER, TOKEN)).isInstanceOf(AppException.class)
                .satisfies(e -> {
                    assertThat(((AppException) e).getStatus().value()).isEqualTo(409);
                    assertThat(((AppException) e).getType().toString()).endsWith("return-inbound-confirmed");
                });
        assertThat(order.getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);
        verify(orders, never()).save(order);
    }

    @Test
    @DisplayName("AC9：未入库的异常照常可确认；提醒走 OPS 去重键 receipt-cleared:{token}，待索赔提示撤回")
    void confirmWithoutInboundAlertsOpsWithDedupe() {
        order.flagLogisticsException(LogisticsException.RETURNED, T0);
        order.setClaimPending(true);
        order.decideReturnCause(com.tailtopia.shop.logistics.ReturnCause.USER_FAULT);
        inboundLine(0);
        assertThat(service.confirmReceipt(USER, TOKEN).getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(ops).send(org.mockito.ArgumentMatchers.eq(com.tailtopia.admin.alert.OpsChannel.OPS),
                org.mockito.ArgumentMatchers.eq("receipt-cleared:" + TOKEN), text.capture());
        assertThat(text.getValue()).contains(DISPLAY_NO).contains("撤回索赔").doesNotContain("Budi");
        verify(alerts, never()).alert(org.mockito.ArgumentMatchers.any(), anyString());
    }
}
