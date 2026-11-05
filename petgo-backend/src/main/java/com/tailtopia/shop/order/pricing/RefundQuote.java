package com.tailtopia.shop.order.pricing;

import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.RefundScope;

/**
 * 退款计算结果（v1.4.2 shop Story 4.1 AC2.1）。不变式：{@code coinRefund + cashRefund = totalRefund}、
 * {@code goodsRefund + shippingRefund + insuranceRefund = totalRefund}（与库级 {@code ck_shop_order_refunds_amounts} 同）。
 *
 * @param lineId      {@code LINE} 时为被取消行的内部 id，否则 null
 * @param qty         {@code LINE} 时为本次取消件数，否则 null
 * @param becameWhole 部分取消到最后一件、按整单处理时为 true（此时 {@code scope = WHOLE}）
 */
public record RefundQuote(RefundScope scope, Long lineId, Integer qty, CancelReason reason,
        long goodsRefund, long shippingRefund, long insuranceRefund, long totalRefund,
        long coinRefund, long cashRefund, boolean becameWhole) {
}
