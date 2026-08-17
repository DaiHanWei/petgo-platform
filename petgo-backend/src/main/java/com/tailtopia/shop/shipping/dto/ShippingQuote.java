package com.tailtopia.shop.shipping.dto;

import java.util.List;

/**
 * 运费试算结果（Story 2.3，FR-99）。
 *
 * <p>🔴 <b>免运抵扣是一条负数行，不是把 fee 改成 0。</b>
 * 结算页要让用户看见「原本运费 20.000，因满额免运 −20.000」——
 * 直接把运费显示成 0 会让用户不知道自己省了钱，免运门槛也就失去了拉高客单价的作用。
 * 对账侧（AB-13D）同理：收入与优惠必须分开记，合并后无法回答「这个月免运送出去多少钱」。
 *
 * <p>{@code total} 恒等于各行之和，且恒 ≥ 0。
 */
public record ShippingQuote(
        String kecamatan,
        long fee,
        long discount,
        long total,
        List<QuoteLine> lines) {

    /** 一行金额。{@code amount} 带符号：运费为正、抵扣为负。 */
    public record QuoteLine(String code, long amount) {
    }

    public static final String LINE_SHIPPING_FEE = "SHIPPING_FEE";
    public static final String LINE_FREE_SHIPPING = "FREE_SHIPPING_DISCOUNT";

    public static ShippingQuote of(String kecamatan, long fee, boolean freeShipping) {
        long discount = freeShipping ? -fee : 0L;
        List<QuoteLine> lines = freeShipping
                ? List.of(new QuoteLine(LINE_SHIPPING_FEE, fee),
                          new QuoteLine(LINE_FREE_SHIPPING, discount))
                : List.of(new QuoteLine(LINE_SHIPPING_FEE, fee));
        return new ShippingQuote(kecamatan, fee, discount, fee + discount, lines);
    }
}
