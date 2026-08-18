package com.tailtopia.shop.dto;

import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.StockStatus;

/**
 * SKU 对外视图（Story 1.1 建，Story 1.2 追加库存三态）。
 *
 * <p>🔴 <b>只暴露 {@code token}，绝不暴露自增 id</b>（NFR-3）。
 * 🔴 {@code returnPolicy} 是 <b>effective 值</b>（SKU 级为空时已继承商品级），前端直接展示即可。
 * 🔴 {@code price} 为最小币种单位整型；格式化 {@code Rp 185.000} 由前端负责。
 *
 * <p><b>库存字段（1.2 追加）：</b>{@code stockStatus} 三态；{@code remaining} 仅在
 * {@link StockStatus#LOW_STOCK} 时有值，🔴 <b>取真实剩余数，不虚构数字</b>（FR-95）。
 * 其余状态为 {@code null}——售罄不必给数字，充足时给数字会泄露经营数据。
 */
public record ShopSkuView(
        String token,
        String specName,
        long price,
        Long netWeightG,
        ReturnPolicy returnPolicy,
        StockStatus stockStatus,
        Long remaining) {
}
