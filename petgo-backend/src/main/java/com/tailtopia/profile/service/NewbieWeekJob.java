package com.tailtopia.profile.service;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.profile.domain.NewbieTask;
import com.tailtopia.profile.repository.UserNewbieMarkDao;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 新手任务 ⑦「注册满一周」每日补记（V1.5.0 onboarding-recall Story 2.1 · AC9 · OR-1）。
 *
 * <p>00:05 WIB 跑（{@code petgo.newbie.week-cron}，env {@code NEWBIE_WEEK_CRON}）：扫 WIB 注册日 ∈ [今天−8, 今天−7]
 * （两天窗口容漏跑）、REAL + USER + ACTIVE + 未注销、尚无 {@code ONE_WEEK} 标记的账号 → 经
 * {@link NewbieMarkService#markIfAbsent} 写 {@code ONE_WEEK}（同时埋点、触发 {@link NewbieMarkHooks} 毕业判定）。
 *
 * <p>🔴 必须早于 19:00 WIB 跑完（召回 D7 依赖，PRD §6）；读进度时 ⑦ 另按注册日直接判定，不依赖本任务时序。
 * <p>分批 500 查「已有标记」；单人失败只 warn（userId + 异常类名），不影响其余人。调度用原生 {@code @Scheduled}。
 */
@Component
public class NewbieWeekJob {

    private static final Logger log = LoggerFactory.getLogger(NewbieWeekJob.class);

    static final int BATCH = 500;

    private final AccountQueryService accounts;
    private final UserNewbieMarkDao marks;
    private final NewbieMarkService markService;
    private final Clock clock;

    @Autowired
    public NewbieWeekJob(AccountQueryService accounts, UserNewbieMarkDao marks, NewbieMarkService markService) {
        this(accounts, marks, markService, Clock.systemUTC());
    }

    NewbieWeekJob(AccountQueryService accounts, UserNewbieMarkDao marks, NewbieMarkService markService,
            Clock clock) {
        this.accounts = accounts;
        this.marks = marks;
        this.markService = markService;
        this.clock = clock;
    }

    @Scheduled(cron = "${petgo.newbie.week-cron:0 5 0 * * *}", zone = "Asia/Jakarta")
    public void run() {
        LocalDate today = LocalDate.now(clock.withZone(ScheduleWindow.WIB));
        int written = runFor(today);
        log.info("newbie one-week scan done: today={} written={}", today, written);
    }

    /** 以 {@code todayWib} 为「今天」跑一轮；返回本轮新写入数。 */
    int runFor(LocalDate todayWib) {
        List<Long> candidates = accounts.realUserIdsRegisteredWibBetween(todayWib.minusDays(8), todayWib.minusDays(7));
        int written = 0;
        for (int i = 0; i < candidates.size(); i += BATCH) {
            List<Long> batch = candidates.subList(i, Math.min(i + BATCH, candidates.size()));
            Set<Long> already = marks.usersHaving(batch, NewbieTask.ONE_WEEK.name());
            for (Long userId : batch) {
                if (already.contains(userId)) {
                    continue;
                }
                try {
                    if (markService.markIfAbsent(userId, NewbieTask.ONE_WEEK)) {
                        written++;
                    }
                } catch (RuntimeException ex) {
                    log.warn("newbie one-week mark failed: userId={} error={}", userId, ex.getClass().getSimpleName());
                }
            }
        }
        return written;
    }
}
