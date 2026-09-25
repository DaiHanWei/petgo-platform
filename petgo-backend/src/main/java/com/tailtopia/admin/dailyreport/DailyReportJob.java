package com.tailtopia.admin.dailyreport;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 飞书群日报定时任务：每天 09:00（WIB）推昨日数据。
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

    @Scheduled(cron = "${petgo.daily-report.cron:0 0 9 * * *}", zone = "Asia/Jakarta")
    public void run() {
        service.pushScheduled();
    }
}
