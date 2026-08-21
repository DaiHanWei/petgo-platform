package com.tailtopia.admin.shop.dto;

import com.tailtopia.shop.domain.StockStatus;

/**
 * 库存管理页的一行（Story 1.4，AB-10C）。
 *
 * <p>🔴 <b>三个数必须并排展示，不得合并、不得只显示可售。</b>AB-10C 原文：客服排查
 * 「用户说买不了但后台显示有货」时，<b>差异一定在锁定库存上</b>——合并显示等于把排查依据删掉。
 *
 * <p>{@code available} 是<b>查询期计算值</b>（{@code actual - locked}），不落库、不新增第三列
 * （继承 V102 表设计与 Story 1.2 口径）。
 */
public record InventoryRowView(
        long skuId,
        String skuToken,
        String productName,
        String specName,
        long actual,
        long locked,
        long available,
        StockStatus stockStatus) {

    public static InventoryRowView of(long skuId, String skuToken, String productName,
            String specName, long actual, long locked, StockStatus status) {
        return new InventoryRowView(skuId, skuToken, productName, specName, actual, locked,
                actual - locked, status);
    }
}
