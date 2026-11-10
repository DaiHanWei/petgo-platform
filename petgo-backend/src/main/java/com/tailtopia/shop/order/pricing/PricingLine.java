package com.tailtopia.shop.order.pricing;

import com.tailtopia.shop.domain.ProductCategory;

/**
 * 计价器输入：一行商品（Story 1.2，D-7）。计价器自有值对象，不依赖任何 JPA 实体。
 *
 * @param skuToken     SKU 对外 token（只透传，不参与计算）
 * @param productToken 商品对外 token（PRODUCT 范围券按它匹配）
 * @param category     品类（CATEGORY 范围券按它匹配）
 * @param unitPrice    原单价（IDR 整数）；🔴 计价器只读不改（NFR7，返佣基数）
 * @param qty          数量
 */
public record PricingLine(String skuToken, String productToken, ProductCategory category,
        long unitPrice, int qty) {
}
