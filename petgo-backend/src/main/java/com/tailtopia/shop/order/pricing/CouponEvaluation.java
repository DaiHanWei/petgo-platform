package com.tailtopia.shop.order.pricing;

/**
 * 计价器输出：单张券在本单的评估（Story 1.2）。
 *
 * @param usable  是否可用（{@code RULE_BETTER} 记为不可用，对应 D10 不可用组）
 * @param reason  不可用原因；可用时为 null
 * @param benefit 若使用可减多少；不可用为 0；运费未知时免运券为 0
 * @param applied 本单是否实际使用
 */
public record CouponEvaluation(String userCouponToken, String couponToken, PricingCouponKind kind,
        boolean usable, CouponUnavailableReason reason, long benefit, boolean applied) {
}
