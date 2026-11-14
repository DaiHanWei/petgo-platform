package com.tailtopia.healthreminder.delivery;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.HealthNotification;
import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.service.HealthReminderAnalytics;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 健康提醒后端埋点（V1.5.0 Story 2.4 · AC6，PRD §4，架构增量 §8）：经 {@link AnalyticsClient}，distinctId 为
 * {@link AnalyticsDistinctId#of} 哈希（不传明文 user_id）。事件名与属性键登记在 {@code AnalyticsEventGuard}（漏登记 = 静默丢）。
 *
 * <p>值：{@code notification_kind} / {@code reminder_kind} / {@code displaced_by} 用附录 C ID 原样（与 PRD AC37
 * 「notification_kind = VACCINE_D_DAY」一致，{@code displaced_by} 另有 {@code scheduled_push}）；{@code push_point}、
 * {@code origin}、{@code reason}、{@code push_result} 小写蛇形。{@code old_app} 作废不发。调用方只在状态迁移成功后调用。
 */
@Component
public class PostHogHealthReminderAnalytics implements HealthReminderAnalytics {

    public static final String EVENT_NOTIFICATION_SENT = "health_notification_sent";
    public static final String EVENT_REMINDER_SENT = "health_reminder_sent";
    public static final String EVENT_REMINDER_CANCELLED = "health_reminder_cancelled";

    private final AnalyticsClient analytics;

    public PostHogHealthReminderAnalytics(AnalyticsClient analytics) {
        this.analytics = analytics;
    }

    @Override
    public void cancelled(HealthReminder row, CancelReason reason, String displacedBy, Long byRecordId) {
        if (reason == CancelReason.OLD_APP) {
            return;
        }
        Map<String, Object> p = reminderBase(row);
        p.put("reason", reason.wire());
        if (displacedBy != null && (reason == CancelReason.SKIPPED_CAP || reason == CancelReason.EXPIRED_DEFER
                || reason == CancelReason.SKIPPED_SCHEDULED_PUSH)) {
            p.put("displaced_by", displacedBy);
        }
        if (byRecordId != null && reason == CancelReason.SATISFIED) {
            p.put("by_record_id", byRecordId);
        }
        analytics.capture(AnalyticsDistinctId.of(row.getOwnerId()), EVENT_REMINDER_CANCELLED, p);
    }

    @Override
    public void notificationSent(HealthNotification n) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("notification_id", n.getPublicToken());
        p.put("pet_id", n.getPetProfileId());
        p.put("notification_kind", n.getNotificationKind().name());
        p.put("push_point", n.getPushPoint().wire());
        p.put("origin", n.getOrigin().wire());
        p.put("push_result", n.getPushResult().name().toLowerCase(Locale.ROOT));
        p.put("merged_count", n.getMergedCount());
        p.put("deferred_days", n.getDeferredDays());
        analytics.capture(AnalyticsDistinctId.of(n.getUserId()), EVENT_NOTIFICATION_SENT, p);
    }

    @Override
    public void reminderSent(HealthReminder row, HealthNotification n) {
        Map<String, Object> p = reminderBase(row);
        p.put("notification_id", n.getPublicToken());
        if (row.getOrigin() == ReminderOrigin.RECORD && row.getSourceRecordId() != null) {
            p.put("record_id", row.getSourceRecordId());
        }
        p.put("deferred_days", row.getDeferredDays());
        analytics.capture(AnalyticsDistinctId.of(row.getOwnerId()), EVENT_REMINDER_SENT, p);
    }

    private static Map<String, Object> reminderBase(HealthReminder row) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (row.getId() != null) {
            p.put("reminder_id", row.getId());
        }
        p.put("pet_id", row.getPetProfileId());
        p.put("reminder_kind", row.getReminderKind().name());
        p.put("push_point", row.getPushPoint().wire());
        p.put("origin", row.getOrigin().wire());
        return p;
    }
}
