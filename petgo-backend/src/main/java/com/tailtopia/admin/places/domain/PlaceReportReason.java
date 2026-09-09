package com.tailtopia.admin.places.domain;

/**
 * 场所举报类型（Story 5.1；CHECK {@code ck_place_reports_reason}）：沿用 {@code moderation.domain.ReportReason} 五值
 * + 场所特有 {@link #DUPLICATE}（重复场所，辅助合并）/ {@link #CLOSED}（已关店）。Java 枚举不能继承，故独立定义，值与 CHECK 全集一致。
 */
public enum PlaceReportReason {
    ILLEGAL,
    MISINFO,
    INAPPROPRIATE,
    HARASSMENT,
    /** 重复场所（PRD AB-17A ②「用户举报重复类目辅助合并」）。 */
    DUPLICATE,
    /** 已关店。 */
    CLOSED,
    OTHER
}
