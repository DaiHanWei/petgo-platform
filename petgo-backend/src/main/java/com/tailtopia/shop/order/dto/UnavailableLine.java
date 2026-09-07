package com.tailtopia.shop.order.dto;

/**
 * 下单时不可用的一行（Story 3.4，FR-95）。
 *
 * <p>🔴 <b>存在的意义就是「不整单打回」</b>：告诉用户<b>具体是哪个 SKU 出了什么问题</b>，
 * 让他移除后继续下单。笼统的「库存不足，请重试」会让用户在一车 8 件商品里
 * <b>逐个试错</b>——那是把平台的排查成本转嫁给了正在掏钱的人。
 */
public record UnavailableLine(String skuToken, String productName, String specName,
        String reason, long available, int requested) {

    /** 商品已下架。 */
    public static final String REASON_DELISTED = "DELISTED";
    /** 可售库存不足（含为 0）。 */
    public static final String REASON_INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
}
