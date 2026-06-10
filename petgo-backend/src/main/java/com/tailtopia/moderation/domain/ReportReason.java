package com.petgo.moderation.domain;

/**
 * 举报类型（Story 3.7，FR-25，落库 varchar UPPER_SNAKE）。用户单选其一。
 */
public enum ReportReason {
    ILLEGAL,        // 违法违规
    MISINFO,        // 虚假信息
    INAPPROPRIATE,  // 不当内容
    HARASSMENT,     // 骚扰
    OTHER           // 其他
}
