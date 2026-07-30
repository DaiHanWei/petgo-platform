package com.tailtopia.order.dto;

/**
 * 订单类型（Story 5.1，泛化订单中心）。前端按类型 + statusCode 本地化 title/subtitle 与图标。
 * {@code ID_HD} 预留——身份证高清下载订单属 Epic 6，本 story 无源不聚合（Epic 6 建流后零改接口接入）。
 */
public enum OrderType {
    VET_CONSULT,
    AI_UNLOCK,
    PAWCOIN_TOPUP,
    ID_HD
}
