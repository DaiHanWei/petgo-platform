package com.tailtopia.merchant.po.service;

import com.tailtopia.merchant.po.domain.PoShippingMethod;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import java.util.List;

/**
 * 建单请求（契约 5.4 的契约字段，已经过格式校验）。🔒 含收货人与门店电话，不得写进日志。
 *
 * @param lines 按请求顺序；{@code lineNo} 由下标 + 1 得出
 */
public record NewPurchaseOrder(List<Line> lines, AddressSnapshot shippingAddress, PoShippingMethod shippingMethod,
        String storeName, String storePhone) {

    public record Line(String skuToken, int qty, long wholesalePrice) {
    }

    @Override
    public String toString() {
        return "NewPurchaseOrder[lines=" + lines.size() + ", PII omitted]";
    }
}
