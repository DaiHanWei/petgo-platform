package com.tailtopia.healthreminder.delivery;

import com.tailtopia.healthreminder.domain.HealthNotification;
import com.tailtopia.healthreminder.domain.ReminderKind;

/** 渲染结果：标题、正文、通知用的 kind（合并时按 FR-166 选）、发出时的落点。🔒 文案禁进日志。 */
public record RenderedCopy(String title, String body, ReminderKind notificationKind, HealthNotification.Route route) {
}
