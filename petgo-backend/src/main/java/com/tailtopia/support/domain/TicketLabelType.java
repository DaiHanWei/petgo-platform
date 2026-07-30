package com.tailtopia.support.domain;

/**
 * 工单标签（Story 4.1，AB-5，落库 varchar UPPER_SNAKE）。多选，同工单去重（唯一 ticket_id+label）。
 */
public enum TicketLabelType {
    BUG,                // 故障
    FEATURE,            // 功能建议
    CONSULT_COMPLAINT,  // 咨询投诉
    REFUND,             // 退款
    CONTENT,            // 内容问题
    ACCOUNT,            // 账号问题
    PRAISE,             // 表扬
    OTHER               // 其他
}
