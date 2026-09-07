package com.tailtopia.shop.returns.domain;

/** 运费承担方（Story 5.1）。 */
public enum ShippingFeeBearer {
    /** 平台承担：退款执行时按用户上传的<b>实际运单金额</b>一并返还（S-7）。 */
    PLATFORM,
    /** 用户承担：用户垫付即最终承担，不返还。 */
    USER
}
