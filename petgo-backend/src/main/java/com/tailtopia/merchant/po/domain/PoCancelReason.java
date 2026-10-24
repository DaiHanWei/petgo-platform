package com.tailtopia.merchant.po.domain;

/** 取消原因（契约 5.2）。新增取值须先改契约再上线 —— 商家平台对未知原因只显示「Dibatalkan」。 */
public enum PoCancelReason {
    /** 60 分钟未付自动取消。 */
    PAYMENT_TIMEOUT
}
