package com.tailtopia.pay.dto;

import com.tailtopia.pay.domain.PaymentIntent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 人类可读支付号（bug 20260721-326 增补，照 299 订单号 {@code OrderDisplayNo} 范式）。计算式，不落库。
 *
 * <p>格式 {@code PAY<用途>-yyyyMMdd-NNNNNN}：前缀标用途（PAYVET / PAYAI / PAYHD / PAYTOPUP），
 * 日期为建意图当天（WIB），序号取 {@code payment_intents} 自增主键 id（同表唯一且单调）。
 * 前缀与订单号（CONSVET/CONSAI/TOPUP）刻意不同名——支付号只出现在支付页与后台支付列表，
 * 防客服拿支付号去订单列表误查到同序号的别家记录。
 *
 * <p>仅展示；对外查询键仍是不可枚举 {@code publicToken}（id 入展示号沿 299 已定先例）。
 */
public final class PaymentDisplayNo {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private PaymentDisplayNo() {
    }

    /** 生成支付号；意图未落库（id/createdAt 为空）时返回 null（调用方回退 token）。 */
    public static String of(PaymentIntent p) {
        if (p.getId() == null || p.getCreatedAt() == null) {
            return null;
        }
        String prefix = switch (p.getPurpose()) {
            case VET_CONSULT -> "PAYVET";
            case AI_UNLOCK -> "PAYAI";
            case ID_HD -> "PAYHD";
            case PAWCOIN_TOPUP -> "PAYTOPUP";
        };
        return prefix + "-" + p.getCreatedAt().atZone(WIB).format(YMD)
                + "-" + String.format("%06d", p.getId());
    }
}
