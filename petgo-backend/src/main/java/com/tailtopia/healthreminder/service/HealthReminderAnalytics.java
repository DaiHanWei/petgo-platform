package com.tailtopia.healthreminder.service;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.HealthNotification;
import com.tailtopia.healthreminder.domain.HealthReminder;

/**
 * 健康提醒埋点出口（V1.5.0 Story 2.1 定义，Story 2.4 接 {@code AnalyticsClient}）。只在状态真正迁移的那一次写库后调用，
 * 同日重跑不重复。属性只放枚举 / 数字 / id，禁宠物名、文案、记录字段原文。
 */
public interface HealthReminderAnalytics {

    /** {@code health_reminder_cancelled}。{@code displacedBy}、{@code byRecordId} 可空。 */
    void cancelled(HealthReminder row, CancelReason reason, String displacedBy, Long byRecordId);

    /** {@code health_notification_sent}：每条通知一次。 */
    void notificationSent(HealthNotification notification);

    /** {@code health_reminder_sent}：通知里每条提醒一次（同一 notification_id）。 */
    void reminderSent(HealthReminder row, HealthNotification notification);
}
