package com.tailtopia.shop.order.dto;

/**
 * 电商订单角标计数（v1.4.2 shop Story 6.2 AC2，F1）：口径与列表分组完全一致（待付款不含宽限期单）。
 */
public record ShopOrderCounts(long pendingPayment, long toReceive) {
}
