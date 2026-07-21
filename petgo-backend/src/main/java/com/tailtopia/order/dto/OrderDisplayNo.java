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

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private OrderDisplayNo() {
    }

    public static String of(String prefix, long id, Instant createdAt) {
        String date = createdAt.atZone(WIB).format(YMD);
        return prefix + "-" + date + "-" + String.format("%06d", id);
    }
}
