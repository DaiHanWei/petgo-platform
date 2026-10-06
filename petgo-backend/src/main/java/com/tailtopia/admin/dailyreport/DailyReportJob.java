package com.tailtopia.admin.dailyreport;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 飞书群日报定时任务：每天 13:00（WIB）推昨日数据。
 *
 * <p>13:00 而非 09:00（2026-10-06）：GA4 普通版完整日数据处理约 12 小时，昨日 00:00 结束后要到中午才基本定稿。
 *
 * <p>🔴 {@code zone} 必须显式写：容器默认 UTC，不写会晚 7 小时（WIB = UTC+7）推送。
 * 调度开关由 {@code AsyncConfig} 的 {@code @EnableScheduling} 统一开启；禁引 Quartz 等调度中间件。
 */
@Component
public class DailyReportJob {

    private final DailyReportService service;

    public DailyReportJob(DailyReportService service) {
        this.service = service;
    }

    @Scheduled(cron = "${petgo.daily-report.cron:0 0 13 * * *}", zone = "Asia/Jakarta")
    public void run() {
        service.pushScheduled();
    }
}
