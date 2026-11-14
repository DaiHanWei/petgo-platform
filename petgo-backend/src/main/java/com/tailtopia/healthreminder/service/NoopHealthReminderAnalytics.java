package com.tailtopia.healthreminder.service;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.HealthReminder;
import org.springframework.stereotype.Component;

/** Story 2.1 的空实现；Story 2.4 以接 {@code AnalyticsClient} 的实现替换。 */
@Component
public class NoopHealthReminderAnalytics implements HealthReminderAnalytics {

    @Override
    public void cancelled(HealthReminder row, CancelReason reason, String displacedBy, Long byRecordId) {
        // Story 2.4 接入
    }
}
