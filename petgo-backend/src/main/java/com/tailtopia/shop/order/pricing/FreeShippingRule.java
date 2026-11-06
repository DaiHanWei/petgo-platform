package com.tailtopia.shop.order.pricing;

/**
 * 计价器输入：满额免运规则（Story 1.2，FR-172）。
 *
 * @param threshold 商品实付门槛；0 = 不做满额免运
 * @param cap       全局封顶 {@code free_shipping_cap}；null 或 0 = 不设额外封顶（只受本单最低报价限制，m7）
 */
public record FreeShippingRule(long threshold, Long cap) {
}
