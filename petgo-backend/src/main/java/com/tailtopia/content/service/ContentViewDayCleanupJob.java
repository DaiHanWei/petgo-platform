package com.tailtopia.content.service;

import com.tailtopia.content.repository.ContentPostViewRepository;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按天浏览（{@code content_post_view_days}）的每日清理（V1.4.2 social-topic · Story 4.1 · AC8.4 · D-3）：
 * 删除 {@code view_date < 今天(WIB) − 30 天} 的行。
 *
 * <p>本方法<b>不开事务</b>：事务在仓储方法上（{@code deleteViewDaysBefore} 自带 {@code @Transactional}），
 * 失败时异常在这里被接住、不会以「rollback-only 提交失败」的形式逃出 catch。
 *
 * <p>DELETE 幂等，不需要 advisory lock（多实例重复执行无副作用）。失败只记日志（异常类名），不重试；下一天自然补删。
 * 日期一律 {@link ScheduleWindow#WIB}（P-6）。
 *
 * <p>⚠️ 追更日提醒去重日志 {@code pet_diary_notify_log} 的清理仍由 Story 1.2 的 {@code PetDiaryNotifyLogCleanupJob}
 * 独立负责（03:30 WIB）。已决定不合并（避免 content→notify 循环依赖），各自每日清理。
 */
@Component
public class ContentViewDayCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ContentViewDayCleanupJob.class);

    /** 保留天数。 */
    static final int RETENTION_DAYS = 30;

    private final ContentPostViewRepository views;
    private final Clock clock;

    @Autowired
    public ContentViewDayCleanupJob(ContentPostViewRepository views) {
        this(views, Clock.systemUTC());
    }

    ContentViewDayCleanupJob(ContentPostViewRepository views, Clock clock) {
        this.views = views;
        this.clock = clock;
    }

    @Scheduled(cron = "${petgo.content.view-day-cleanup-cron:0 40 3 * * *}", zone = "Asia/Jakarta")
    public void cleanup() {
        LocalDate cutoff = cutoff();
        try {
            int deleted = views.deleteViewDaysBefore(cutoff);
            log.info("content_post_view_days cleanup: cutoff={} deleted={}", cutoff, deleted);
        } catch (RuntimeException e) {
            log.warn("content_post_view_days 清理失败 cutoff={} cause={}", cutoff, e.getClass().getSimpleName());
        }
    }

    /** 截止日 = 今天（WIB）− 30 天；严格小于它的行被删。 */
    LocalDate cutoff() {
        return LocalDate.now(clock.withZone(ScheduleWindow.WIB)).minusDays(RETENTION_DAYS);
    }
}
