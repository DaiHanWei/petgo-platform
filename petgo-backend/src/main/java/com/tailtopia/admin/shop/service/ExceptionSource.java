package com.tailtopia.admin.shop.service;

import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import java.util.Optional;

/**
 * 异常订单队列来源（V1.4.2 shop-admin Story 5.1 · AD-9 · G15）。由 {@link #of} 一个函数算出，优先级：
 * 退回 &gt; 丢件破损 &gt; 揽收失败 &gt; 运单失败 &gt; 缺货（重发失败的已发货单来源为退回）。
 *
 * <ul>
 *   <li>缺货：待发货 ∧ 已标记缺货；</li>
 *   <li>运单失败：{@code waybill_failed_at} 非空 ∧ 待发货 / 已发货；</li>
 *   <li>揽收失败：{@code PICKUP_FAILED} ∧ 待发货 / 已发货；</li>
 *   <li>退回：{@code RETURNED} ∧ 待发货 / 已发货 / 已签收（O-29：已签收只开重发，待发货只展示）；</li>
 *   <li>丢件破损：{@code LOST / DAMAGED} ∧（已发货，或已签收 / 已完成且尚无赔付单）。</li>
 * </ul>
 * CANCELLED / REFUNDING / REFUNDED（及其余状态）一律不在队列。
 */
public enum ExceptionSource {
    RETURNED,
    LOST_DAMAGED,
    PICKUP_FAILED,
    WAYBILL_FAILED,
    OUT_OF_STOCK;

    /** 判定所需的订单事实（纯数据，便于表驱动单测）。 */
    public record Facts(ShopOrderStatus status, boolean shortageFlagged, boolean waybillFailed,
            LogisticsException logisticsException, boolean hasCompensation) {
    }

    public static Optional<ExceptionSource> of(Facts f) {
        ShopOrderStatus st = f.status();
        if (st == null) {
            return Optional.empty();
        }
        boolean pending = st == ShopOrderStatus.PENDING_SHIPMENT;
        boolean shipped = st == ShopOrderStatus.SHIPPED;
        boolean deliveredOrDone = st == ShopOrderStatus.DELIVERED || st == ShopOrderStatus.COMPLETED;
        LogisticsException le = f.logisticsException();
        if (le == LogisticsException.RETURNED && (pending || shipped || st == ShopOrderStatus.DELIVERED)) {
            return Optional.of(RETURNED);
        }
        if ((le == LogisticsException.LOST || le == LogisticsException.DAMAGED)
                && (shipped || (deliveredOrDone && !f.hasCompensation()))) {
            return Optional.of(LOST_DAMAGED);
        }
        if (le == LogisticsException.PICKUP_FAILED && (pending || shipped)) {
            return Optional.of(PICKUP_FAILED);
        }
        if (f.waybillFailed() && (pending || shipped)) {
            return Optional.of(WAYBILL_FAILED);
        }
        if (f.shortageFlagged() && pending) {
            return Optional.of(OUT_OF_STOCK);
        }
        return Optional.empty();
    }
}
