package com.tailtopia.healthreminder.web;

import java.util.List;

/**
 * 点开健康提醒的落点（V1.5.0 Story 2.4 · AC5，架构增量 §5.4 逐字）。Jackson NON_NULL：空字段省略。
 *
 * @param status       {@code OK} / {@code PET_UNAVAILABLE}
 * @param route        {@code FORM} / {@code PAGE} / {@code PAGE_SATISFIED}
 * @param safetyBoxes  {@code PREGNANT}、{@code PERMETHRIN}（怀孕在前）
 * @param analytics    App 直接用来报 {@code health_notification_opened}
 */
public record HealthNotificationOpenResponse(String status, Long petId, String route, Long focusRecordId,
        Prefill prefill, List<String> safetyBoxes, Analytics analytics) {

    /** 表单预填（FORM 路由）。{@code eventDateEmpty=true}：建议类 / 体检，日期留空让用户选。 */
    public record Prefill(String type, String vaccinePreset, String vaccineName, Integer dose, String dewormType,
            String customName, boolean eventDateEmpty) {
    }

    /** 埋点属性：kind 用附录 C ID，pushPoint / origin 小写蛇形。 */
    public record Analytics(String notificationId, String notificationKind, String pushPoint, String origin,
            int mergedCount) {
    }
}
