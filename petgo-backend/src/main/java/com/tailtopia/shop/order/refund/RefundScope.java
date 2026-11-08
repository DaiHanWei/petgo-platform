package com.tailtopia.shop.order.refund;

/**
 * 取消退款单范围（v1.4.2 shop Story 4.1 AC1.3，架构 D-15；落 {@code shop_order_refunds.scope}）。
 *
 * <ul>
 *   <li>{@code WHOLE}：整单取消（含部分取消到最后一件转整单、迟到款退款）；</li>
 *   <li>{@code LINE}：一行的部分件数；</li>
 *   <li>{@code COMPENSATION}：签收 / 完成后才报破损的赔付（S-9），订单状态不变、不动 {@code refunded_qty}。</li>
 * </ul>
 */
public enum RefundScope {
    WHOLE,
    LINE,
    COMPENSATION
}
