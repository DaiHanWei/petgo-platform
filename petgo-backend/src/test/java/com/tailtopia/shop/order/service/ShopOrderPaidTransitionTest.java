package com.tailtopia.shop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.event.ShopOrderPaidEvent;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.service.InventoryService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

/** L0：统一「已付款」入口（v1.4.2 shop Story 3.2 AC4）。 */
class ShopOrderPaidTransitionTest {

    private final ShopOrderRepository orders = mock(ShopOrderRepository.class);
    private final ShopOrderLineRepository orderLines = mock(ShopOrderLineRepository.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final CouponLedgerService coupons = mock(CouponLedgerService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ShopOrderPaidTransition transition =
            new ShopOrderPaidTransition(orders, orderLines, inventory, coupons, events);

    @Test
    @DisplayName("非待支付 → 返回 false，不出库、不写 paid_at、不动券、不发事件")
    void notPendingIsNoop() {
        ShopOrder o = ShopOrderTestOrders.newModeOrder(1L, 9L, 0L, CheckoutSource.CART);
        o.markPaid(java.time.Instant.now());

        assertThat(transition.markPaid(o, PaidSource.GATEWAY)).isFalse();
        verifyNoInteractions(inventory, coupons, events);
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("🔴 正常路径：commit → markPaid（paid_at）→ coupons.markUsed → 发 ShopOrderPaidEvent(…, payChannel, source)")
    void happyPathOrder() {
        ShopOrder o = ShopOrderTestOrders.newModeOrder(1L, 9L, 220_000L, CheckoutSource.CART);
        when(orderLines.findByOrderIdOrderByIdAsc(1L)).thenReturn(List.of(
                ShopOrderLine.of(1L, 11L, "P", "S", 100_000L, 1, ReturnPolicy.RETURNABLE),
                ShopOrderLine.of(1L, 12L, "P", "S", 50_000L, 2, ReturnPolicy.RETURNABLE)));
        when(orders.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(transition.markPaid(o, PaidSource.PURE_COIN)).isTrue();

        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
        assertThat(o.getPaidAt()).isNotNull();
        InOrder seq = inOrder(inventory, orders, coupons, events);
        seq.verify(inventory).commit(11L, 1);
        seq.verify(inventory).commit(12L, 2);
        seq.verify(orders).save(o);
        seq.verify(coupons).markUsed(eq(1L), any());
        ArgumentCaptor<ShopOrderPaidEvent> ev = ArgumentCaptor.forClass(ShopOrderPaidEvent.class);
        seq.verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue()).isEqualTo(new ShopOrderPaidEvent(1L, "tok1", 9L, 220_000L,
                "PAWCOIN", "PURE_COIN"));
    }

    @Test
    @DisplayName("paid_at 只写第一次：重复调用第二次返回 false")
    void idempotent() {
        ShopOrder o = ShopOrderTestOrders.newModeOrder(1L, 9L, 0L, CheckoutSource.CART);
        when(orders.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(transition.markPaid(o, PaidSource.GATEWAY)).isTrue();
        java.time.Instant first = o.getPaidAt();
        assertThat(transition.markPaid(o, PaidSource.GATEWAY)).isFalse();
        assertThat(o.getPaidAt()).isEqualTo(first);
        verify(inventory, never()).commit(anyLong(), anyInt());
    }
}
