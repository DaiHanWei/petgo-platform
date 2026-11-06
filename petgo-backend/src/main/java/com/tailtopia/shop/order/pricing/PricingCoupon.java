package com.tailtopia.shop.order.pricing;

import java.time.Instant;
import java.util.Set;

/**
 * 计价器输入：一张可参与本单的用户券（Story 1.2，D-7）。
 *
 * <p>由调用方（3.1 / 3.2）从券领域对象转换而来；计价器不判断归属 / 锁定 / 模板状态（AC6）。
 *
 * @param userCouponToken 用户券 public_token（同一模板可持有多张，以它为标识）
 * @param couponToken     券模板 public_token（只为埋点透传）
 * @param kind            满减 / 免运
 * @param amount          满减额；免运券 = 每张封顶额
 * @param minSpend        门槛，0 = 无门槛
 * @param scopeType       适用范围类型
 * @param scopeValues     CATEGORY：{@code ProductCategory} 名；PRODUCT：商品 public_token
 * @param validFrom       生效时刻（含）
 * @param validUntil      失效时刻（不含）
 */
public record PricingCoupon(String userCouponToken, String couponToken, PricingCouponKind kind,
        long amount, long minSpend, PricingScopeType scopeType, Set<String> scopeValues,
        Instant validFrom, Instant validUntil) {
}
