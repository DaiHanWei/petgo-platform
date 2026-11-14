package com.tailtopia.healthreminder.service;

import com.tailtopia.profile.health.LaunchDateProvider;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * 有效上线日 = {@code health_reminder_settings.launch_date}；尚未上线（为空）→ 今天 WIB（V1.5.0 Story 2.1 · AC1.3，
 * 取代 1.2 的默认实现）。回填静默以它为界。
 */
@Component
public class SettingsLaunchDateProvider implements LaunchDateProvider {

    private final HealthReminderSettingsDao settings;

    public SettingsLaunchDateProvider(HealthReminderSettingsDao settings) {
        this.settings = settings;
    }

    @Override
    public LocalDate effectiveLaunchDate(LocalDate today) {
        LocalDate launch = settings.launchDate();
        return launch == null ? today : launch;
    }
}
