package com.tailtopia.shop.coupon.service;

import com.tailtopia.shop.coupon.repository.CouponExpiryNotifyLogDao;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 券快过期提醒定时任务（v1.4.2 shop Story 2.3 AC5 · D-22 · I8 · FR-130.10）：每天 WIB 10:00，
 * 窗口 {@code (now, now + 48h]}，按用户合并，单用户处理见 {@link CouponExpiryReminderService}。
 *
 * <ul>
 *   <li>防重入：外层事务 {@code pg_try_advisory_xact_lock(hashtext('coupon_expiry_reminder'))}，拿不到即跳过本轮
 *       （照 {@code TopicHeatJob}）；外层持锁期间逐用户 REQUIRES_NEW，一人失败不影响他人；</li>
 *   <li>候选分批 {@value #BATCH} 人（{@code user_id} keyset），本轮最多 {@value #MAX_USERS_PER_RUN} 人，剩余下一天再扫（WARN）；</li>
 *   <li>收尾删 {@code wib_date < 今天 − }{@value #LOG_RETENTION_DAYS}{@code  天} 的提醒日志；</li>
 *   <li>INFO 一行汇总（候选人数 / 发送数 / 跳过数 / 失败数），不记券 token、券名、手机号。</li>
 * </ul>
 * 原生 {@code @Scheduled} + DB，禁 MQ / Quartz / 缓存层。测试环境 cron = {@code "-"} 关闭，直调 {@link #run(Instant)}。
 */
@Component
public class CouponExpiryReminderJob {

    private static final Logger log = LoggerFactory.getLogger(CouponExpiryReminderJob.class);

    static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_xact_lock(hashtext('coupon_expiry_reminder'))";
    static final Duration WINDOW = Duration.ofHours(48);
    static final int BATCH = 500;
    static final int MAX_USERS_PER_RUN = 20_000;
    static final int LOG_RETENTION_DAYS = 30;

    /** 一轮的计数：{@code skipped} = 没拿到锁。 */
    public record Result(boolean skipped, int candidates, int sent, int markedOnly, int skippedUsers, int failed) {
    }

    private final UserCouponRepository userCoupons;
    private final CouponExpiryNotifyLogDao logDao;
    private final CouponExpiryReminderService reminder;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired
    public CouponExpiryReminderJob(UserCouponRepository userCoupons, CouponExpiryNotifyLogDao logDao,
            CouponExpiryReminderService reminder, JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this(userCoupons, logDao, reminder, jdbc, txManager, Clock.systemUTC());
    }

    CouponExpiryReminderJob(UserCouponRepository userCoupons, CouponExpiryNotifyLogDao logDao,
            CouponExpiryReminderService reminder, JdbcTemplate jdbc, PlatformTransactionManager txManager,
            Clock clock) {
        this.userCoupons = userCoupons;
        this.logDao = logDao;
        this.reminder = reminder;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** 定时入口：每天 WIB 10:00（{@code petgo.shop.coupon.expiry-reminder-cron}）。兜底 catch 只记异常类名。 */
    @Scheduled(cron = "${petgo.shop.coupon.expiry-reminder-cron:0 0 10 * * *}", zone = "Asia/Jakarta")
    public void scheduled() {
        try {
            run(clock.instant());
        } catch (RuntimeException e) {
            log.warn("券快过期提醒任务失败 cause={}", e.getClass().getSimpleName());
        }
    }

    /** 一轮提醒（{@code now} = 本轮开始时刻）。拿不到 advisory 锁即跳过、什么都不写。 */
    public Result run(Instant now) {
        Instant until = now.plus(WINDOW);
        Result r = tx.execute(status -> {
            Boolean locked = jdbc.queryForObject(TRY_LOCK_SQL, Boolean.class);
            if (!Boolean.TRUE.equals(locked)) {
                return new Result(true, 0, 0, 0, 0, 0);
            }
            int candidates = 0;
            int sent = 0;
            int markedOnly = 0;
            int skipped = 0;
            int failed = 0;
            long after = 0L;
            boolean capped = false;
            while (true) {
                List<Long> ids = userCoupons.findExpiryReminderUserIds(now, until, after, BATCH);
                if (ids.isEmpty()) {
                    break;
                }
                for (Long userId : ids) {
                    if (candidates >= MAX_USERS_PER_RUN) {
                        capped = true;
                        break;
                    }
                    candidates++;
                    after = userId;
                    try {
                        switch (reminder.remindUser(userId, now, until)) {
                            case SENT -> sent++;
                            case MARKED_ONLY -> markedOnly++;
                            case SKIPPED -> skipped++;
                            case FAILED -> failed++;
                        }
                    } catch (RuntimeException e) {
                        failed++;
                        log.warn("券快过期提醒单用户处理失败 userId={} cause={}", userId, e.getClass().getSimpleName());
                    }
                }
                if (capped || ids.size() < BATCH) {
                    break;
                }
            }
            if (capped) {
                log.warn("券快过期提醒本轮达上限 {} 人，剩余下一天再扫", MAX_USERS_PER_RUN);
            }
            LocalDate cutoff = CouponExpiryCopy.wibDate(now).minusDays(LOG_RETENTION_DAYS);
            logDao.deleteBefore(cutoff);
            return new Result(false, candidates, sent, markedOnly, skipped, failed);
        });
        if (r != null && r.skipped()) {
            log.info("coupon expiry reminder skipped: lock held by another run");
        } else if (r != null) {
            log.info("券快过期提醒完成 candidates={} sent={} markedOnly={} skipped={} failed={}",
                    r.candidates(), r.sent(), r.markedOnly(), r.skippedUsers(), r.failed());
        }
        return r;
    }
}
