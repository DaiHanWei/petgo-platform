package com.tailtopia.order.dto;

import java.time.Instant;

/**
 * 统一订单卡片视图（Story 5.1，泛化 4 类）。**不下发 title/subtitle 显示串**——前端按 {@code orderType}+{@code statusCode}
 * 本地化（i18n 契约，App 不渲染后端串）。{@code amount} 可空（HD/待接单预留；本 story 3 类恒非 null）。
 *
 * @param orderType   订单类型（VET_CONSULT/AI_UNLOCK/PAWCOIN_TOPUP/ID_HD）
 * @param orderToken  对外不可枚举订单号（详情用）
 * @param statusCode  状态码（前端本地化 + 5-3 详情分支）
 * @param statusColor 状态色语义（WARN/INFO/SUCCESS；退款中 INFO）
 * @param amount      金额 IDR（可空——泛化预留；3 类恒非 null）
 * @param payChannel  支付渠道（QRIS/PAWCOIN；可空）
 * @param createdAt   建单时间（跨源合并排序键）
 * @param thumbnailUrl 商品主图（Story 3.9 电商卡片；其余 4 类恒 null）
 * @param itemTitle    首个商品名 + 规格（电商；其余 4 类恒 null）
 * @param itemCount    件数（电商，多商品时前端展示「等 N 件」；其余 4 类恒 null）
 */
public record OrderSummaryView(
        String orderType,
        String orderToken,
        String displayNo,
        String statusCode,
        String statusColor,
        Long amount,
        String payChannel,
        Instant createdAt,
        String thumbnailUrl,
        String itemTitle,
        Integer itemCount) {

    /**
     * 既有 4 类的 8 参构造（Story 3.9 追加字段时保留）。
     *
     * <p>🔴 <b>存在的唯一理由是让既有四个映射器一行都不用改</b>（并行契约 O-1）：
     * record 加组件会让所有构造点编译失败，而那正是三线共享文件里最容易撞车的一种改动。
     * 商品信息只有电商卡片有，其余 4 类恒 null —— 这不是"暂时为空"，是这些类型本就没有商品。
     */
    public OrderSummaryView(String orderType, String orderToken, String displayNo,
            String statusCode, String statusColor, Long amount, String payChannel,
            Instant createdAt) {
        this(orderType, orderToken, displayNo, statusCode, statusColor, amount, payChannel,
                createdAt, null, null, null);
    }
}
