package com.tailtopia.order.dto;

import java.time.Instant;

/**
 * 订单详情视图（Story 5.3，按 orderType 分支）。**不下发 title/subtitle 显示串**——前端按 orderType+statusCode+refundStage
 * 本地化。宠物已删（FR-54D）→ {@code petDeleted=true} + pet 字段 null（订单仍存活，非 500）。
 *
 * @param orderType         订单类型
 * @param orderToken        对外订单号
 * @param statusCode        状态码
 * @param statusColor       状态色语义
 * @param amount            金额（可空——泛化/HD 预留；3 类恒非 null）
 * @param payChannel        支付渠道（可空）
 * @param createdAt         建单时间
 * @param paidAt            到账时间（可空；充值无独立 paidAt）
 * @param petName           宠物名（兽医；已删/无→null）
 * @param petType           宠物类型（CAT/DOG/OTHER；已删→null）
 * @param petAvatarUrl      宠物头像（已删→null）
 * @param petDeleted        宠物是否已删（FR-54D，兽医订单 pet 已硬删时 true）
 * @param sessionStartedAt  会话起（兽医，可空）
 * @param sessionEndedAt    会话止（兽医，可空）
 * @param refundStage       退款子阶段（非退款态→null）
 * @param refundNetAmount   退款到手净额（填收款后有值，可空）
 * @param coins             到账 koin（充值 = amount；非充值→null）
 * @param triageTaskId      分诊任务 id（AI，深链预留，可空）
 */
public record OrderDetailView(
        String orderType,
        String orderToken,
        String statusCode,
        String statusColor,
        Long amount,
        String payChannel,
        Instant createdAt,
        Instant paidAt,
        String petName,
        String petType,
        String petAvatarUrl,
        boolean petDeleted,
        Instant sessionStartedAt,
        Instant sessionEndedAt,
        String refundStage,
        Long refundNetAmount,
        Long coins,
        Long triageTaskId) {
}
