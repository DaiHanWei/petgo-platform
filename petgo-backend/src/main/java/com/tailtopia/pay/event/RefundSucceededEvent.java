package com.tailtopia.pay.event;

import com.tailtopia.pay.domain.PaymentPurpose;

/**
 * 退款成功领域事件（V1.4.0 ① Story 1.3 · 架构 AD-9，过去式）。凡<b>真实退钱处</b>（P-3 / P-4）在退款状态落库的
 * 同一事务内发布；消费方：① 按比例扣回对应消费的成长值（AFTER_COMMIT）。
 *
 * @param refundKey       退款记录唯一键（扣回幂等）
 * @param userId          被退款的用户
 * @param purpose         原消费用途
 * @param bizRefType      业务单类型（如 {@code CONSULT_ORDER} / {@code SHOP_ORDER}）
 * @param bizRefId        业务单号（问诊订单 id / 电商订单 id）
 * @param paymentIntentId 原支付意图（纯币为 null）
 * @param refundAmount    本次退款额（IDR）
 * @param originalAmount  原消费实付额（IDR）
 */
public record RefundSucceededEvent(String refundKey, long userId, PaymentPurpose purpose, String bizRefType,
        String bizRefId, Long paymentIntentId, long refundAmount, long originalAmount) {
}
