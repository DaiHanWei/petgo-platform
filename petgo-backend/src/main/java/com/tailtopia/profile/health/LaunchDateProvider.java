package com.tailtopia.profile.health;

import java.time.LocalDate;

/**
 * 「有效上线日」（V1.5.0 Story 1.2，架构增量 §3.4 回填静默）：回填记录的 Next date 早于它 → 不显示、不提醒。
 *
 * <p>真实值在 {@code health_reminder_settings.launch_date}（Story 2.1 建表）；未上线前 = 今天（WIB）。
 * Story 1.2 只提供 {@link TodayLaunchDateProvider}；2.1 用读表实现替换（{@code @Primary}）。
 */
public interface LaunchDateProvider {

    /** @param today 今天（WIB），未上线时直接返回它 */
    LocalDate effectiveLaunchDate(LocalDate today);
}
