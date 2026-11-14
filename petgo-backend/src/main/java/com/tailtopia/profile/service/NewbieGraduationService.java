package com.tailtopia.profile.service;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.profile.domain.AccountMilestoneFlag;
import com.tailtopia.profile.domain.NewbieTask;
import com.tailtopia.profile.repository.UserMilestoneFlagRepository;
import com.tailtopia.profile.repository.UserNewbieMarkDao;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 新手毕业判定（V1.5.0 onboarding-recall Story 2.2 · FR-177 · OR-6）——{@link NewbieMarkHooks} 的实现。
 *
 * <p>{@link #checkAndGraduate}：①–⑥ 标记齐全 <b>且</b> ⑦（WIB 注册日 + 7 ≤ 今天）<b>且</b> 账号是 REAL USER ACTIVE 未注销
 * → {@link AccountMilestoneService#onFlagTriggered}（{@code LULUS_PEMULA}；已有 flag 时它本身 no-op，这里先查一次省一次锁）。
 * 无宠物账号凑不齐 ①（发 Diary 要先有宠物），自然不会毕业。
 *
 * <p>调用点：任何新标记写入并<b>提交后</b>（{@link NewbieMarkService}）、{@code NewbieWeekJob} 写 ⑦ 后、健康记录创建时
 * （{@code MilestoneCompletionService.maybeUnlockLulusPemulaFor*} 委托过来，PRD「录健康记录时的徽章检查保留」）。
 *
 * <p>事务：类级 {@code REQUIRES_NEW}（经代理的 {@link #afterMarked} / {@link #checkAndGraduate}）。{@link NewbieMarkService}
 * 在 {@code TransactionSynchronization.afterCommit} 里调本类 —— 那时原事务已提交但资源仍绑在线程上，按 REQUIRED 加入
 * 的写入不会再提交（Spring 文档明令此处用 REQUIRES_NEW）；真正的写在 {@code onFlagTriggered}（也是 REQUIRES_NEW）里。
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class NewbieGraduationService implements NewbieMarkHooks {

    /** ①–⑥：必须有标记的六项（⑦ 按注册日判定）。 */
    static final Set<NewbieTask> MARKED_TASKS = Set.of(NewbieTask.FIRST_DIARY, NewbieTask.FIRST_LIKE_RECEIVED,
            NewbieTask.FIRST_FOLLOW, NewbieTask.FIRST_LIKE_GIVEN, NewbieTask.FIRST_COMMENT, NewbieTask.FIRST_REPLY);

    private final UserNewbieMarkDao marks;
    private final UserMilestoneFlagRepository flags;
    private final AccountQueryService accounts;
    private final AccountMilestoneService accountMilestones;
    private final Clock clock;

    @Autowired
    public NewbieGraduationService(UserNewbieMarkDao marks, UserMilestoneFlagRepository flags,
            AccountQueryService accounts, AccountMilestoneService accountMilestones) {
        this(marks, flags, accounts, accountMilestones, Clock.systemUTC());
    }

    NewbieGraduationService(UserNewbieMarkDao marks, UserMilestoneFlagRepository flags, AccountQueryService accounts,
            AccountMilestoneService accountMilestones, Clock clock) {
        this.marks = marks;
        this.flags = flags;
        this.accounts = accounts;
        this.accountMilestones = accountMilestones;
        this.clock = clock;
    }

    @Override
    public void afterMarked(long userId, NewbieTask task) {
        checkAndGraduate(userId);
    }

    /**
     * 判定并（满足时）解锁账号级 Lulus Pemula。
     *
     * @return 本次是否发起了解锁（已毕业 / 条件不满足 → false）
     */
    public boolean checkAndGraduate(long userId) {
        if (flags.existsByUserIdAndFlagKey(userId, AccountMilestoneFlag.LULUS_PEMULA.name())) {
            return false;
        }
        Set<String> done = marks.keysOf(userId);
        for (NewbieTask t : MARKED_TASKS) {
            if (!done.contains(t.name())) {
                return false;
            }
        }
        Instant created = accounts.createdAtOf(userId).orElse(null);
        if (created == null || !oneWeekReached(created, clock.instant())) {
            return false;
        }
        if (!accounts.isActiveRegularUser(userId)) {
            return false;
        }
        accountMilestones.onFlagTriggered(userId, AccountMilestoneFlag.LULUS_PEMULA);
        return true;
    }

    /** ⑦：WIB 注册日 + 7 ≤ 今天（WIB）。 */
    static boolean oneWeekReached(Instant createdAt, Instant now) {
        LocalDate reg = createdAt.atZone(ScheduleWindow.WIB).toLocalDate();
        LocalDate today = now.atZone(ScheduleWindow.WIB).toLocalDate();
        return !reg.plusDays(NewbieTaskService.ONE_WEEK_DAYS).isAfter(today);
    }
}
