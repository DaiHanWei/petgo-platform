package com.tailtopia.shop.order.dto;

import java.util.List;

/** 电商订单列表一页（v1.4.2 shop Story 6.2 AC1.4）：{@code nextCursor} 仅 {@code hasMore} 时下发。 */
public record ShopOrderListPage(List<ShopOrderListItem> items, String nextCursor, boolean hasMore) {

    public static ShopOrderListPage empty() {
        return new ShopOrderListPage(List.of(), null, false);
    }
}
