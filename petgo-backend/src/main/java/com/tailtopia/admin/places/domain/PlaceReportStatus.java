package com.tailtopia.admin.places.domain;

/** 场所举报处置状态（Story 5.1；CHECK {@code ck_place_reports_status}）。处置流在 Story 5.4 接进 A1 统一队列。 */
public enum PlaceReportStatus {
    PENDING,
    /** 驳回。 */
    DISMISSED,
    /** 已处置（下架 / 合并等）。 */
    ACTIONED
}
