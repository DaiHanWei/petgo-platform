package com.tailtopia.notify.schedule;

import com.tailtopia.notify.repository.PetDiaryNotifyLogDao;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 追更日提醒去重日志的每日清理（V1.4.2 social-topic · Story 1.2 · AC4.4 · D-5）：删除 {@code wib_date < 今天(WIB) − 30 天}
 * 的行。删宠 / 注销后的残留行（表不建 FK）也由这里带走。
 *
 * <p>⚠️ 架构 D-5 写「随按天浏览一起清理」，按天浏览表在 Story 4.1 才建 —— 本 story 先自建，4.1 可合并进同一任务。
 * 失败只记日志（异常类名），不重试；下一天自然补删。日期一律 {@link ScheduleWindow#WIB}（P-6）。
 */
@Component
public class PetDiaryNotifyLogCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(PetDiaryNotifyLogCleanupJob.class);

    /** 保留天数。 */
    static final int RETENTION_DAYS = 30;

    private final PetDiaryNotifyLogDao dao;
    private final Clock clock;

    @Autowired
    public PetDiaryNotifyLogCleanupJob(PetDiaryNotifyLogDao dao) {
        this(dao, Clock.systemUTC());
    }

    PetDiaryNotifyLogCleanupJob(PetDiaryNotifyLogDao dao, Clock clock) {
        this.dao = dao;
        this.clock = clock;
    }

    @Scheduled(cron = "${petgo.pet-diary-notify-log.cleanup-cron:0 30 3 * * *}", zone = "Asia/Jakarta")
    public void cleanup() {
        LocalDate cutoff = cutoff();
        try {
            int deleted = dao.deleteBefore(cutoff);
            log.info("pet_diary_notify_log cleanup: cutoff={} deleted={}", cutoff, deleted);
        } catch (RuntimeException e) {
            log.warn("pet_diary_notify_log 清理失败 cutoff={} cause={}", cutoff, e.getClass().getSimpleName());
        }
    }

    /** 截止日 = 今天（WIB）− 30 天；严格小于它的行被删。 */
    LocalDate cutoff() {
        return LocalDate.now(clock.withZone(ScheduleWindow.WIB)).minusDays(RETENTION_DAYS);
    }
}
