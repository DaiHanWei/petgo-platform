package com.tailtopia.order.dto;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 人类可读订单号（bug 20260721-299/326）。计算式，不落库、不需迁移、老数据同样有号。
 *
 * <p>格式 {@code PREFIX-yyyyMMdd-NNNNNN}：前缀标识来源功能（CONSVET 兽医问诊 / CONSAI AI 问诊 /
 * TOPUP 充值），日期为建单当天（WIB），序号取订单自增主键 id（同表唯一且单调 → 有序、唯一）。
 * 客服可据此对账；对外仍以不可枚举 {@code orderToken} 作查询键，本号仅展示。
 */
public final class OrderDisplayNo {

    public static final String VET_CONSULT = "CONSVET";
    public static final String AI_UNLOCK = "CONSAI";
    public static final String TOPUP = "TOPUP";
    /**
     * 精选自营电商（Story 3.9）。🔴 <b>与虚拟商品订单号前缀隔离</b> ——
     * 财务要能一眼区分自营实物与虚拟商品收入（后台 AB-13D 对账）。
     */
    public static final String ECOMMERCE = "TOKO";
    /**
     * V1.3.2 Story 3.6：一次性解锁三类的订单号前缀。🔴 与支付号 {@code PAYTS / PAYPASS / PAYBP} <b>刻意不同名</b>
     * （同 {@code PaymentDisplayNo} 类注释：订单号与支付号是两张不同的凭证，客服不能混着念）。
     */
    public static final String TAILSONALITY = "TSL";
    public static final String PASSPORT_SNAP = "PASPOR";
    public static final String BOARDING_PASS = "BPASS";

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private OrderDisplayNo() {
    }

    /** 一次性解锁 sku → 订单号前缀（订单中心与后台异常页同一个出口）。 */
    public static String keepsakePrefix(com.tailtopia.purchase.domain.KeepsakeSku sku) {
        return switch (sku) {
            case TAILSONALITY -> TAILSONALITY;
            case PASSPORT_SNAP -> PASSPORT_SNAP;
            case BOARDING_PASS -> BOARDING_PASS;
        };
    }

    public static String of(String prefix, long id, Instant createdAt) {
        String date = createdAt.atZone(WIB).format(YMD);
        return prefix + "-" + date + "-" + String.format("%06d", id);
    }
}
