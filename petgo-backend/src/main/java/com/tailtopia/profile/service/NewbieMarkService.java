package com.tailtopia.profile.service;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.profile.domain.NewbieTask;
import com.tailtopia.profile.repository.UserNewbieMarkDao;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 新手任务标记写入（V1.5.0 onboarding-recall Story 2.1 · OR-1）。
 *
 * <h2>🔴 写库纪律</h2>
 * 由 {@code NewbieTaskListener}（{@code @Async} + {@code AFTER_COMMIT}）经代理调用；本方法 {@code REQUIRES_NEW}
 * ——原事务已提交，不另开事务写入会静默不提交（2026-07 事故）。本类不自调用事务方法。
 *
 * <h2>只在真正新写入时</h2>
 * {@code INSERT … ON CONFLICT DO NOTHING} 返回 1 才：① 同步上报 {@code newbie_task_completed {task, days_since_signup}}
 * （{@link AnalyticsClient#capture}，同步、不加 {@code @Async}，同 {@code MilestoneAnalyticsListener}）；
 * ② 调 {@link NewbieMarkHooks#afterMarked}（Story 2-2 毕业判定）。并发两次同一任务只有一次返回 1。
 * 补算（Story 2-4，纯 SQL）不经本类、不报埋点。
 */
@Service
public class NewbieMarkService {

    static final String EVENT_TASK_COMPLETED = "newbie_task_completed";

    private final UserNewbieMarkDao marks;
    private final AccountQueryService accounts;
    private final AnalyticsClient analytics;
    private final ObjectProvider<NewbieMarkHooks> hooks;
    private final Clock clock;

    @Autowired
    public NewbieMarkService(UserNewbieMarkDao marks, AccountQueryService accounts, AnalyticsClient analytics,
            ObjectProvider<NewbieMarkHooks> hooks) {
        this(marks, accounts, analytics, hooks, Clock.systemUTC());
    }

    NewbieMarkService(UserNewbieMarkDao marks, AccountQueryService accounts, AnalyticsClient analytics,
            ObjectProvider<NewbieMarkHooks> hooks, Clock clock) {
        this.marks = marks;
        this.accounts = accounts;
        this.analytics = analytics;
        this.hooks = hooks;
        this.clock = clock;
    }

    /**
     * 记一条新手任务（已有则什么都不做）。
     *
     * @return 本次是否真正新写入
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markIfAbsent(long userId, NewbieTask task) {
        Instant now = clock.instant();
        if (marks.insertIfAbsent(userId, task.name(), UserNewbieMarkDao.SOURCE_RUNTIME, now) != 1) {
            return false;
        }
        analytics.capture(AnalyticsDistinctId.of(userId), EVENT_TASK_COMPLETED, Map.of(
                "task", task.ordinalForAnalytics(),
                "days_since_signup", daysSinceSignup(userId, now)));
        hooks.orderedStream().forEach(h -> h.afterMarked(userId, task));
        return true;
    }

    /** WIB 注册日到今天（WIB）的天数（≥ 0；取不到注册时间按 0）。 */
    private long daysSinceSignup(long userId, Instant now) {
        return accounts.createdAtOf(userId)
                .map(created -> Math.max(0, ChronoUnit.DAYS.between(wibDate(created), wibDate(now))))
                .orElse(0L);
    }

    static LocalDate wibDate(Instant at) {
        return at.atZone(ScheduleWindow.WIB).toLocalDate();
    }
}
