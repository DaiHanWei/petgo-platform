package com.tailtopia.shop.coupon.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * 券的对外视图（v1.4.2 shop Story 2.2 AC11，D-6 / P-5 camelCase / NFR4）。由 {@link CouponViews} 的工厂构造。
 *
 * <p>🔴 <b>不下发任何自增 id、{@code issued_count}、{@code total_quantity}、兑换码</b>
 * （{@code CouponViewContractTest} 钉住字段名）。
 *
 * @param couponToken          模板 public_token（埋点 coupon_id 用它）
 * @param userCouponToken      已领时为用户券 public_token；未领为 null
 * @param kind                 {@code DISCOUNT | FREE_SHIPPING}
 * @param nameId               运营录入的印尼语券名
 * @param nameEn               运营录入的英语券名
 * @param scopeType            {@code ALL | CATEGORY | PRODUCT}
 * @param scopeCategories      CATEGORY 时为品类码；否则空
 * @param scopeProductCount    PRODUCT 时为商品数；否则 null（不下发商品 token 列表）
 * @param validFrom            已领：物化值；未领 FIXED：模板值；未领 RELATIVE：null
 * @param validUntil           同上
 * @param validDays            未领 RELATIVE：领后 N 天；否则 null
 * @param expiringSoon         已领 AVAILABLE 且 {@code validUntil − now ≤ 48h}
 * @param isNew                刚兑换成功（G6「Baru」标）
 * @param status               已领：{@code AVAILABLE | LOCKED | USED}；未领：null
 * @param lockedByPendingOrder C-3：被待付款订单占用（App 置灰、标「已被待付款订单占用」）
 * @param usedAt               C-9：USED 时的使用时刻（取用户券转 USED 时写入的 {@code updated_at}）
 * @param usedOrderNo          C-9：使用该券的订单展示号
 * @param usedOrderToken       C-9：使用该券的订单 public_token（点进订单）
 */
public record CouponView(
        String couponToken,
        String userCouponToken,
        String kind,
        String nameId,
        String nameEn,
        long amount,
        long minSpend,
        String scopeType,
        List<String> scopeCategories,
        Integer scopeProductCount,
        Instant validFrom,
        Instant validUntil,
        Integer validDays,
        boolean expiringSoon,
        boolean claimed,
        boolean newbie,
        @JsonProperty("isNew") boolean isNew,
        String status,
        boolean lockedByPendingOrder,
        Instant usedAt,
        String usedOrderNo,
        String usedOrderToken) {
}
