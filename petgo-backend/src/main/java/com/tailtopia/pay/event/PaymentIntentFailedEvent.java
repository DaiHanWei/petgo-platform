package com.tailtopia.pay.event;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentFailureCategory;
import com.tailtopia.pay.domain.PaymentPurpose;

/**
 * 支付意图失败事件（Story 1-2 · AD-S9(b)）。与 {@link PaymentIntentPaidEvent} 对称的失败那一支。
 *
 * <p><b>为什么非有不可</b>：网关拒付走
 * {@code PaymentIntentService.applyCallback} 的 {@code case FAILED}，<b>完全不经过 {@code shop/} 的
 * 任何代码</b>——订单仍是 {@code PENDING_PAYMENT}，shop 侧看不见这件事发生过。没有这个事件，
 * 「拒付」这一支就没有任何可订阅的钩子，支付漏斗永远缺一角。
 *
 * <p>🔴 <b>不带 {@code gatewayMeta}、不带 {@code gatewayRef}</b>：事件对象会被监听器读到，
 * meta 是网关回调原文，字段构成由第三方决定，随时可能多出持卡人姓名、手机号一类内容。
 * 失败原因已由 {@link PaymentFailureCategory} 归成三个受控枚举值下发，够用。
 *
 * @param intentId        意图主键（内部用，不外泄）
 * @param publicToken     对外意图 token
 * @param userId          付款用户
 * @param purpose         用途（监听方据此只认自己那条业务线）
 * @param channel         渠道
 * @param amount          金额（最小币种单位整型）
 * @param currency        币种
 * @param failureCategory 失败类别，由 {@link PaymentFailureCategory#of} 在写库之后算出
 */
public record PaymentIntentFailedEvent(long intentId, String publicToken, long userId,
        PaymentPurpose purpose, PayChannel channel, long amount, String currency,
        PaymentFailureCategory failureCategory) {
}
