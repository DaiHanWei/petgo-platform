package com.tailtopia.shop.order.pricing;

/**
 * 计价器输出：满额免运进度（FR-127.3，基数 = 商品实付）。
 *
 * @param threshold 门槛
 * @param remaining 还差多少（≥ 0）
 */
public record FreeShippingProgress(long threshold, long remaining) {
}
