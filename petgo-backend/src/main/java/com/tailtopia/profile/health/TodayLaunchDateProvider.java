package com.tailtopia.profile.health;

import java.time.LocalDate;
import org.springframework.stereotype.Component;

/** 默认实现：尚未上线，有效上线日 = 今天（WIB）。Story 2.1 以读 {@code health_reminder_settings} 的实现替换。 */
@Component
public class TodayLaunchDateProvider implements LaunchDateProvider {

    @Override
    public LocalDate effectiveLaunchDate(LocalDate today) {
        return today;
    }
}
