package com.tailtopia.order.dto;

/**
 * 订单类型（Story 5.1，泛化订单中心）。前端按类型 + statusCode 本地化 title/subtitle 与图标。
 * {@code ID_HD} 预留——身份证高清下载订单属 Epic 6，本 story 无源不聚合（Epic 6 建流后零改接口接入）。
 */
public enum OrderType {
    VET_CONSULT,
    AI_UNLOCK,
    PAWCOIN_TOPUP,
    ID_HD,
    /**
     * 精选自营电商（V1.4.0 Story 3.9，FR-101 —— FR-54 的第 5 类卡片）。
     *
     * <p>🔴 <b>只在末尾追加</b>（并行契约 O-1）。本枚举不落库（全仓无 {@code order_type} 列），
     * 故无需迁移；但它是前端筛选值与卡片分支的依据，中间插值会让另两条线的取值错位。
     */
    ECOMMERCE
}
