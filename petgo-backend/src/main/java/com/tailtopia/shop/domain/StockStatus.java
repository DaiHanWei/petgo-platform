package com.tailtopia.shop.domain;

/**
 * SKU 展示用库存状态（Story 1.2，FR-95）。由可售库存与运营阈值计算得出，<b>不落库</b>。
 *
 * <p>🔴 <b>售罄不自动下架</b>——商品仍可被列表与详情查询到，保留复购提醒与外部落点。
 */
public enum StockStatus {
    /** 可售 = 0：展示 {@code Stok habis}，加购按钮禁用 */
    OUT_OF_STOCK,
    /** 可售 ≤ 运营阈值：展示 {@code Sisa {n}}，🔴 n 取真实剩余数，不虚构 */
    LOW_STOCK,
    IN_STOCK
}
