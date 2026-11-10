package com.tailtopia.shop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * L0（V1.4.2 shop-admin Story 7.2 · AD-11 · AC5）：未锁库存行 —— 转已付款不确认库存；全部取消即视为已处置；
 * 联系用户后继续成功后清标记。发货入口的拒绝走 {@code ShopOrderLineRepository.existsUnresolvedStockUnlocked}（L1）。
 */
class StockUnlockedGuardTest {

    private static ShopOrderLine line(long skuId, int qty, boolean unlocked) {
        ShopOrderLine l = ShopOrderLine.of(1L, skuId, "P", "1kg", 10_000L, qty, ReturnPolicy.RETURNABLE);
        if (unlocked) {
            l.markStockUnlocked();
        }
        return l;
    }

    @Test
    void unresolvedUntilFullyCancelledOrCleared() {
        ShopOrderLine l = line(9, 2, true);
        assertThat(l.isUnresolvedStockUnlocked()).isTrue();
        l.addRefundedQty(1);
        assertThat(l.isUnresolvedStockUnlocked()).as("还剩 1 件未取消").isTrue();
        l.addRefundedQty(1);
        assertThat(l.isUnresolvedStockUnlocked()).as("全部取消 = 已处置").isFalse();

        ShopOrderLine c = line(9, 1, true);
        c.clearStockUnlocked();
        assertThat(c.isUnresolvedStockUnlocked()).isFalse();
        assertThat(line(9, 1, false).isUnresolvedStockUnlocked()).isFalse();
    }

    @Test
    void markPaidSkipsCommitForUnlockedLines() {
        var orders = mock(com.tailtopia.shop.order.repository.ShopOrderRepository.class);
        var lines = mock(com.tailtopia.shop.order.repository.ShopOrderLineRepository.class);
        var inventory = mock(com.tailtopia.shop.service.InventoryService.class);
        var coupons = mock(com.tailtopia.shop.coupon.service.CouponLedgerService.class);
        var events = mock(org.springframework.context.ApplicationEventPublisher.class);
        var order = com.tailtopia.shop.order.domain.ShopOrder.place("tok", "TOKO-1", java.time.Instant.EPOCH, 1L,
                20_000L, 0L, 0L, new com.tailtopia.shop.order.domain.AddressSnapshot("B", "+62811", "DKI", "JS", "KB",
                        "Jl", "12160"));
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", 7L);
        when(lines.findByOrderIdOrderByIdAsc(7L)).thenReturn(List.of(line(1, 2, false), line(2, 3, true)));
        var t = newTransition(orders, lines, inventory, coupons, events);
        assertThat(t.markPaid(order, PaidSource.REORDER)).isTrue();
        verify(inventory).commit(1L, 2L);
        verify(inventory, never()).commit(org.mockito.ArgumentMatchers.eq(2L), anyLong());
    }

    private static ShopOrderPaidTransition newTransition(Object... deps) {
        // 构造参数随版本增减：按类型匹配注入，避免本测试与构造签名强耦合
        for (var ctor : ShopOrderPaidTransition.class.getDeclaredConstructors()) {
            Object[] args = new Object[ctor.getParameterCount()];
            boolean ok = true;
            for (int i = 0; i < args.length; i++) {
                Class<?> type = ctor.getParameterTypes()[i];
                Object match = null;
                for (Object d : deps) {
                    if (type.isInstance(d)) {
                        match = d;
                    }
                }
                if (match == null) {
                    match = mock(type);
                }
                args[i] = match;
            }
            if (ok) {
                try {
                    ctor.setAccessible(true);
                    return (ShopOrderPaidTransition) ctor.newInstance(args);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalStateException("no ctor");
    }
}
