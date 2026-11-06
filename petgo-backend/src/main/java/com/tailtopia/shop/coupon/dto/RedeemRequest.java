package com.tailtopia.shop.coupon.dto;

/** 兑换码请求（Story 2.2 AC3）。🔴 码明文不进日志。 */
public record RedeemRequest(String code) {

    /** 防止 record 默认 toString 把码明文带进日志 / 异常信息。 */
    @Override
    public String toString() {
        return "RedeemRequest[code=***]";
    }
}
