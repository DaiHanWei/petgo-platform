package com.tailtopia.shop.coupon.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.error.ProblemExtensions;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 提交订单时所选用户券已不可用（v1.4.2 shop Story 2.1 AC8，D-3 / P-5）：409 {@code coupon-invalid}，
 * 扩展成员 {@code userCouponTokens}（失效的用户券 public_token）。照 {@code CheckoutUnavailableException} 写法：
 * 继承 {@link AppException}（否则落进 catch-all 变 500），明细经 {@link ProblemExtensions} 进入 RFC 9457 扩展成员。
 *
 * <p>3.2 提交事务内抛它，整个提交事务回滚；App 走 D17「券已失效、已为你重新选择」。
 */
public class CouponInvalidException extends AppException implements ProblemExtensions {

    private final transient List<String> userCouponTokens;

    public CouponInvalidException(Collection<String> userCouponTokens) {
        super(HttpStatus.CONFLICT, ErrorTypes.COUPON_INVALID, "所选优惠券已失效，请重新选择");
        this.userCouponTokens = List.copyOf(userCouponTokens);
    }

    public CouponInvalidException(String userCouponToken) {
        this(List.of(userCouponToken));
    }

    public List<String> getUserCouponTokens() {
        return userCouponTokens;
    }

    @Override
    public Map<String, Object> problemExtensions() {
        return Map.of("userCouponTokens", userCouponTokens);
    }
}
