package com.tailtopia.shop.dto;

import com.tailtopia.shop.domain.ReturnPolicy;

/**
 * SKU 对外视图（Story 1.1）。
 *
 * <p>🔴 <b>只暴露 {@code token}，绝不暴露自增 id</b>（NFR-3）。
 * 🔴 {@code returnPolicy} 是 <b>effective 值</b>（SKU 级为空时已继承商品级），前端直接展示即可。
 * 🔴 {@code price} 为最小币种单位整型；格式化 {@code Rp 185.000} 由前端负责。
 *
 * <p>⚠️ 本 Story <b>不含库存/售罄字段</b>——{@code sku_inventory} 属 Story 1.2。
 */
public record ShopSkuView(
        String token,
        String specName,
        long price,
        Long netWeightG,
        ReturnPolicy returnPolicy) {
}
