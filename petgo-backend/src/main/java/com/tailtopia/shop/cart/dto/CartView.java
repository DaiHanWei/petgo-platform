package com.tailtopia.shop.cart.dto;

import java.util.List;

/**
 * 购物车视图（Story 3.1，FR-96）。
 *
 * <p>🔴 <b>失效商品单独成组</b>（{@code invalidLines}）：已下架或已售罄的行
 * <b>不参与合计、不可勾选</b>，但<b>也不静默消失</b>——用户加过什么应该看得见，
 * 悄悄删掉会让他以为自己记错了，而下架/售罄恰恰是最需要解释的时刻。
 *
 * <p>🔴 <b>{@code itemCount} 是件数不是种类数</b>：Tab 角标显示 3 而车里有 3 种商品共 7 件，
 * 会让用户以为漏加了。角标要跟用户脑子里的"我买了几件"对上。
 *
 * <p>🔴 <b>单店模型：没有店铺分组</b>——平台自营是唯一卖家。
 */
public record CartView(
        List<CartLine> lines,
        List<CartLine> invalidLines,
        long subtotal,
        int itemCount) {

    /**
     * @param invalidReason 失效原因；有效行为 {@code null}。
     *     🔴 <b>要区分「下架」与「售罄」</b>——前者是永久的（换个商品吧），
     *     后者是暂时的（可以等），给同一句话会让用户做错决定。
     */
    public record CartLine(
            String skuToken,
            String productToken,
            String productName,
            String specName,
            long price,
            int qty,
            String mainImageUrl,
            Long availableStock,
            String invalidReason) {
    }

    /** 失效原因：商品已下架。 */
    public static final String REASON_DELISTED = "DELISTED";
    /** 失效原因：该规格已售罄。 */
    public static final String REASON_OUT_OF_STOCK = "OUT_OF_STOCK";
}
