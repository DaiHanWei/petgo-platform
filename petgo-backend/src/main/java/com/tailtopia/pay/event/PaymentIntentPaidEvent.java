package com.tailtopia.pay.event;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentPurpose;

/**
 * 支付意图到账事件（Story 1.1）。{@link com.tailtopia.pay.service.PaymentIntentService} 在意图
 * {@code PENDING → PAID} 后发布，供后续 story 消费的<b>入账 hook</b>：
 * <ul>
 *   <li>Story 1.2/1.3：{@code PAWCOIN_TOPUP} → 双分录记账 + PawCoin 钱包入账；</li>
 *   <li>Epic 2/3/6：解锁/建会话/发货等下游动作。</li>
 * </ul>
 *
 * <p><b>本 story 不带任何消费者</b>——只落事件发布点，避免 1.2/1.3 反向改动本模块（brownfield 增量）。
 * 消费者须在到账<b>同事务</b>内以 {@code @TransactionalEventListener(BEFORE_COMMIT)} 或直接同步入账，
 * 禁 {@code AFTER_COMMIT} 异步（见 Story 1.3 Dev Notes 血泪引用：AFTER_COMMIT + 默认 REQUIRED 会静默吞写）。
 *
 * @param intentId    意图主键（内部用，不外泄）
 * @param publicToken 对外订单号
 * @param userId      付款用户
 * @param purpose     用途（决定下游入账分支）
 * @param channel     渠道
 * @param amount      金额（最小币种单位整型）
 * @param currency    币种
 */
public record PaymentIntentPaidEvent(long intentId, String publicToken, long userId,
        PaymentPurpose purpose, PayChannel channel, long amount, String currency) {
}
