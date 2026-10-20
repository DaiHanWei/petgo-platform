package com.tailtopia.profile.slot;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.profile.slot.domain.PetSlotRental;
import com.tailtopia.profile.slot.domain.PetSlotRentalStatus;
import com.tailtopia.profile.slot.repository.PetSlotRentalRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 月租名额到期扫描的业务（V1.4.0 multi-pet Story 2.3 · AD-7 · FR-146.4 · AC11）。调度见 {@link PetSlotExpiryScanner}。
 *
 * <p>一次 {@link #scan} = <b>先到期、后提醒</b>（同一次扫描里已到期的名额不会再收到「即将到期」）：
 * <ol>
 *   <li><b>到期</b>：{@code ACTIVE AND expires_at <= now} 的用户（每轮上限 {@link #USER_BATCH}，剩余下一轮）逐个
 *       {@link #expireForUser}——<b>逐用户独立事务</b>（REQUIRES_NEW）：{@link PetSlotLock#lockUser}（与 reconcile / 续租到账
 *       同一把账号锁）→ 加锁后<b>重读</b>到期名额（并发续租已推后的不再命中）→ 置 {@code EXPIRED}、{@code expired_at = now}
 *       → {@code reconcile(RENTAL_EXPIRED)} 得 {@code demoted}；<b>提交后</b> {@code demoted} 非空才发一条
 *       {@code PET_SLOT_EXPIRED}。</li>
 *   <li><b>提醒</b>：{@code ACTIVE AND reminded_at IS NULL AND now < expires_at <= now + 3 天} 的用户逐个
 *       {@link #remindForUser}：逐名额条件置 {@code reminded_at}（仍在窗口且未提醒才命中）→ 提交后对<b>实际置位</b>的名额，
 *       按 WIB 到期自然日合并、每日一条 {@code PET_SLOT_EXPIRING}。</li>
 * </ol>
 *
 * <p><b>幂等 / 可重放（NFR3）</b>：处理标记就是状态列（{@code status} / {@code reminded_at}），重复扫描不重复处理、不重复通知；
 * 单个用户失败只回滚自己（名额保持原状，下一轮重做），不影响他人；通知在状态提交之后发（at-most-once：发送失败只记 WARN，
 * 不回滚、不重发）。单实例部署 + 状态列幂等，不加分布式锁、不引 MQ（AD-7）。
 *
 * <p>文案照 {@code OPS_ANNOUNCEMENT}「读后端 title / body」（K1）：按收件人账号语言渲染好落库，推送经
 * {@link NotificationService#sendWithCopy} 用同一键同一 args 渲染。🔒 日志只记 userId / 计数 / 异常类名，不记宠物名。
 */
@Service
public class PetSlotExpiryService {

    private static final Logger log = LoggerFactory.getLogger(PetSlotExpiryService.class);

    /** 每轮每阶段最多处理的用户数（剩余下一轮）。 */
    static final int USER_BATCH = 500;

    /** 到期前提醒窗口：{@code expires_at <= now + 3 天}（含边界）。 */
    static final Duration REMIND_WINDOW = Duration.ofDays(3);

    private final PetSlotRentalRepository rentals;
    private final PetSlotLock lock;
    private final PetSlotStateService state;
    private final NotificationService notifications;
    private final AccountQueryService accounts;
    private final PetSlotExpiryCopy copy;
    private final TransactionOperations newTx;

    @Autowired
    public PetSlotExpiryService(PetSlotRentalRepository rentals, PetSlotLock lock, PetSlotStateService state,
            NotificationService notifications, AccountQueryService accounts, PetSlotExpiryCopy copy,
            PlatformTransactionManager txManager) {
        this(rentals, lock, state, notifications, accounts, copy, requiresNew(txManager));
    }

    PetSlotExpiryService(PetSlotRentalRepository rentals, PetSlotLock lock, PetSlotStateService state,
            NotificationService notifications, AccountQueryService accounts, PetSlotExpiryCopy copy,
            TransactionOperations newTx) {
        this.rentals = rentals;
        this.lock = lock;
        this.state = state;
        this.notifications = notifications;
        this.accounts = accounts;
        this.copy = copy;
        this.newTx = newTx;
    }

    private static TransactionOperations requiresNew(PlatformTransactionManager txManager) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    /** 一次扫描的计数（只计数，不含任何宠物名 / 用户名）。 */
    public record ScanResult(int expiredUsers, int expiredRentals, int expiredNotices, int remindedRentals,
            int remindNotices, int failedUsers) {

        public boolean hasWork() {
            return expiredRentals > 0 || remindedRentals > 0 || failedUsers > 0;
        }
    }

    /** 单个用户的到期处理结果。{@code demotedNames} 含宠物名：🔒 不得写日志。 */
    public record ExpireOutcome(int expiredRentals, List<String> demotedNames, boolean notified) {
    }

    /** 单个用户的提醒结果：实际置位的名额数、发出的通知数（同一 WIB 到期日合并为一条）。 */
    public record RemindOutcome(int remindedRentals, int notices) {
    }

    /** 先到期、后提醒（AC1.3）。不在事务内：每个用户各自 REQUIRES_NEW。 */
    public ScanResult scan(Instant now) {
        int expiredUsers = 0;
        int expiredRentals = 0;
        int expiredNotices = 0;
        int failed = 0;
        for (Long userId : rentals.findUserIdsWithDueExpiry(PetSlotRentalStatus.ACTIVE, now,
                PageRequest.of(0, USER_BATCH))) {
            try {
                ExpireOutcome o = expireForUser(userId, now);
                if (o.expiredRentals() > 0) {
                    expiredUsers++;
                    expiredRentals += o.expiredRentals();
                }
                if (o.notified()) {
                    expiredNotices++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("宠物名额到期处理失败（下一轮重试）userId={} cause={}", userId, e.getClass().getSimpleName());
            }
        }
        int remindedRentals = 0;
        int remindNotices = 0;
        Instant until = now.plus(REMIND_WINDOW);
        for (Long userId : rentals.findUserIdsWithDueReminder(PetSlotRentalStatus.ACTIVE, now, until,
                PageRequest.of(0, USER_BATCH))) {
            try {
                RemindOutcome o = remindForUser(userId, now);
                remindedRentals += o.remindedRentals();
                remindNotices += o.notices();
            } catch (RuntimeException e) {
                failed++;
                log.warn("宠物名额到期提醒失败（下一轮重试）userId={} cause={}", userId, e.getClass().getSimpleName());
            }
        }
        return new ScanResult(expiredUsers, expiredRentals, expiredNotices, remindedRentals, remindNotices, failed);
    }

    /**
     * 单个用户的到期处理（独立事务；抛异常 = 该用户整体回滚、名额仍 {@code ACTIVE}，由 {@link #scan} 记 WARN 后继续）。
     * 事务提交后，{@code demoted} 非空才发一条 {@code PET_SLOT_EXPIRED}（无宠物因此只读时不发）。
     */
    public ExpireOutcome expireForUser(long userId, Instant now) {
        ExpireOutcome done = newTx.execute(status -> {
            lock.lockUser(userId);
            List<PetSlotRental> due = rentals.findByUserIdAndStatusAndExpiresAtLessThanEqualOrderByIdAsc(userId,
                    PetSlotRentalStatus.ACTIVE, now); // 加锁后重读：续租可能已把 expires_at 推后
            if (due.isEmpty()) {
                return new ExpireOutcome(0, List.of(), false);
            }
            due.forEach(r -> r.markExpired(now));
            rentals.saveAllAndFlush(due);
            ReconcileResult result = state.reconcile(userId, PetSlotTrigger.RENTAL_EXPIRED);
            List<String> names = result.demoted().stream().map(ReconcileResult.PetRef::name).toList();
            return new ExpireOutcome(due.size(), names, false);
        });
        if (done == null || done.demotedNames().isEmpty()) {
            return done == null ? new ExpireOutcome(0, List.of(), false) : done;
        }
        boolean sent = notify(userId, NotificationType.PET_SLOT_EXPIRED, PetSlotExpiryCopy.EXPIRED,
                locale -> copy.expiredArgs(done.demotedNames()));
        return new ExpireOutcome(done.expiredRentals(), done.demotedNames(), sent);
    }

    /**
     * 单个用户的到期前提醒（独立事务）：逐名额条件置 {@code reminded_at}，只对<b>实际置位</b>的名额发提醒；
     * 同一 WIB 到期自然日合并为一条，不同到期日各一条（AC4.3）。
     */
    public RemindOutcome remindForUser(long userId, Instant now) {
        Instant until = now.plus(REMIND_WINDOW);
        Set<LocalDate> alreadyNotified = new java.util.HashSet<>();
        List<Instant> marked = newTx.execute(status -> {
            // 先取「之前轮次已提醒」的到期日（必须在本轮置位之前读，否则会把本轮自己算进去）。
            rentals.findRemindedExpiries(userId, PetSlotRentalStatus.ACTIVE, now)
                    .forEach(e -> alreadyNotified.add(PetSlotExpiryCopy.wibDate(e)));
            List<Instant> out = new ArrayList<>();
            for (PetSlotRental r : rentals.findReminderCandidates(userId, PetSlotRentalStatus.ACTIVE, now, until)) {
                if (rentals.markReminded(r.getId(), PetSlotRentalStatus.ACTIVE, now, until) == 1) {
                    out.add(r.getExpiresAt());
                }
            }
            return out;
        });
        if (marked == null || marked.isEmpty()) {
            return new RemindOutcome(0, 0);
        }
        SortedSet<LocalDate> days = new TreeSet<>();
        marked.forEach(e -> days.add(PetSlotExpiryCopy.wibDate(e)));
        // 同一到期日之前轮次已经发过一条 → 只置位不重发（合并口径跨轮次也成立）。
        days.removeAll(alreadyNotified);
        int notices = 0;
        for (LocalDate day : days) {
            if (notify(userId, NotificationType.PET_SLOT_EXPIRING, PetSlotExpiryCopy.EXPIRING,
                    locale -> copy.expiringArgs(day, locale))) {
                notices++;
            }
        }
        return new RemindOutcome(marked.size(), notices);
    }

    /**
     * 状态已提交后发一条通知：按收件人 locale 渲染 title / body 落库，推送由 {@code sendWithCopy} 同键同 args 渲染。
     * 失败只记 WARN（at-most-once，不回滚状态、不重发）。🔒 不记宠物名。
     */
    private boolean notify(long userId, NotificationType type, String copyKey, Function<Locale, Object[]> argsFor) {
        try {
            Locale locale = accounts.localeOf(userId);
            Object[] args = argsFor.apply(locale);
            String title = copy.render(copyKey, "title", args, locale);
            String body = copy.render(copyKey, "body", args, locale);
            notifications.sendWithCopy(userId, type, copyKey, args, title, body, null);
            return true;
        } catch (RuntimeException e) {
            log.warn("宠物名额通知发送失败（不重发）userId={} type={} cause={}", userId, type,
                    e.getClass().getSimpleName());
            return false;
        }
    }
}
