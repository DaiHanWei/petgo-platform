package com.tailtopia.entitlement.service;

import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.service.EntitlementExpiryGrouping.Batch;
import com.tailtopia.entitlement.service.EntitlementExpiryGrouping.Group;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 免费次数到期提醒（V1.5.0 gift-pack Story 4-4 · AC4 · D-27）：每天 10:00 WIB（{@code 0 0 3 * * *} UTC）。
 *
 * <ul>
 *   <li>防重入：外层事务 {@code pg_try_advisory_xact_lock(hashtext('entitlement_expiry_reminder'))}，拿不到即跳过本轮
 *       （照 {@code CouponExpiryReminderJob}）；</li>
 *   <li>候选：{@code used < quantity AND expires_at ∈ (now, now + 7 天] AND expiry_notified_at IS NULL}，按用户 keyset 分批；</li>
 *   <li>每个（用户, 大类）一个独立事务：条件置 {@code expiry_notified_at}（只认仍为 NULL 的批）→ 发一条
 *       {@code PACK_CREDIT_EXPIRING}（{@code sendWithoutPush}，不推送）；发送失败整组回滚、下次重试（WARN 只记 userId / family）；</li>
 *   <li>注销 / 注销受理中 / 虚拟号：只置位不发（免得每天重扫）。</li>
 * </ul>
 * 标题 / 正文按收件人语言从 {@code messages*.properties} 取后落库。不改 {@code ck_notifications_type}、不推送。
 */
@Component
public class EntitlementExpiryReminderJob {

    private static final Logger log = LoggerFactory.getLogger(EntitlementExpiryReminderJob.class);

    static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_xact_lock(hashtext('entitlement_expiry_reminder'))";
    static final Duration WINDOW = Duration.ofDays(7);
    static final int BATCH_USERS = 500;
    static final String DEEP_LINK_TYPE = "PACK_CREDIT_EXPIRING";

    private static final String CANDIDATE_USERS_SQL = """
            SELECT DISTINCT user_id FROM entitlement_credits
             WHERE used < quantity AND expires_at > :now AND expires_at <= :until AND expiry_notified_at IS NULL
               AND user_id > :after
             ORDER BY user_id LIMIT :lim
            """;
    private static final String BATCHES_SQL = """
            SELECT id, user_id, credit_type, quantity - used AS remaining, expires_at FROM entitlement_credits
             WHERE user_id = :uid AND used < quantity AND expires_at > :now AND expires_at <= :until
               AND expiry_notified_at IS NULL
             ORDER BY id
            """;
    private static final String MARK_SQL = """
            UPDATE entitlement_credits SET expiry_notified_at = :now
             WHERE id IN (:ids) AND expiry_notified_at IS NULL
            """;

    /** 一轮的计数。 */
    public record Result(boolean skipped, int groups, int sent, int markedOnly, int failed) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationService notifications;
    private final AccountQueryService accounts;
    private final AccountDeletionRepository deletions;
    private final MessageSource messages;
    private final TransactionTemplate outerTx;
    private final TransactionTemplate groupTx;
    private final Clock clock;

    @Autowired
    public EntitlementExpiryReminderJob(NamedParameterJdbcTemplate jdbc, NotificationService notifications,
            AccountQueryService accounts, AccountDeletionRepository deletions, MessageSource messages,
            PlatformTransactionManager txManager) {
        this(jdbc, notifications, accounts, deletions, messages, txManager, Clock.systemUTC());
    }

    EntitlementExpiryReminderJob(NamedParameterJdbcTemplate jdbc, NotificationService notifications,
            AccountQueryService accounts, AccountDeletionRepository deletions, MessageSource messages,
            PlatformTransactionManager txManager, Clock clock) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.accounts = accounts;
        this.deletions = deletions;
        this.messages = messages;
        this.outerTx = new TransactionTemplate(txManager);
        this.groupTx = new TransactionTemplate(txManager);
        this.groupTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @Scheduled(cron = "${petgo.entitlement.expiry-reminder-cron:0 0 3 * * *}", zone = "UTC")
    public void scheduled() {
        try {
            Result r = run(clock.instant());
            if (!r.skipped() && r.groups() > 0) {
                log.info("entitlement expiry reminder groups={} sent={} markedOnly={} failed={}", r.groups(), r.sent(),
                        r.markedOnly(), r.failed());
            }
        } catch (RuntimeException e) {
            log.warn("entitlement expiry reminder failed cause={}", e.getClass().getSimpleName());
        }
    }

    /** 一轮提醒；拿不到 advisory 锁即跳过、什么都不写。 */
    public Result run(Instant now) {
        Instant until = now.plus(WINDOW);
        OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        OffsetDateTime to = OffsetDateTime.ofInstant(until, ZoneOffset.UTC);
        return outerTx.execute(status -> {
            Boolean locked = jdbc.getJdbcTemplate().queryForObject(TRY_LOCK_SQL, Boolean.class);
            if (!Boolean.TRUE.equals(locked)) {
                return new Result(true, 0, 0, 0, 0);
            }
            int groups = 0;
            int sent = 0;
            int markedOnly = 0;
            int failed = 0;
            long after = 0L;
            while (true) {
                List<Long> users = jdbc.queryForList(CANDIDATE_USERS_SQL, new MapSqlParameterSource("now", at)
                        .addValue("until", to).addValue("after", after).addValue("lim", BATCH_USERS), Long.class);
                if (users.isEmpty()) {
                    break;
                }
                for (Long uid : users) {
                    for (Group g : EntitlementExpiryGrouping.group(batchesOf(uid, at, to))) {
                        groups++;
                        switch (remindGroup(g, at)) {
                            case SENT -> sent++;
                            case MARKED_ONLY -> markedOnly++;
                            case FAILED -> failed++;
                            case NONE -> { }
                        }
                    }
                }
                after = users.get(users.size() - 1);
            }
            return new Result(false, groups, sent, markedOnly, failed);
        });
    }

    enum Outcome { SENT, MARKED_ONLY, FAILED, NONE }

    private List<Batch> batchesOf(long uid, OffsetDateTime now, OffsetDateTime until) {
        List<Batch> out = new ArrayList<>();
        jdbc.query(BATCHES_SQL, new MapSqlParameterSource("uid", uid).addValue("now", now).addValue("until", until),
                rs -> {
                    CreditType type = CreditType.fromWire(rs.getString("credit_type")).orElse(null);
                    if (type == null) {
                        return;
                    }
                    out.add(new Batch(rs.getLong("id"), rs.getLong("user_id"), type.family(), rs.getInt("remaining"),
                            rs.getObject("expires_at", OffsetDateTime.class).toInstant()));
                });
        return out;
    }

    /** 一组一个独立事务：置位 → 发通知；发送失败整组回滚（下次重试）。 */
    private Outcome remindGroup(Group g, OffsetDateTime now) {
        try {
            Outcome o = groupTx.execute(status -> {
                int marked = jdbc.update(MARK_SQL, new MapSqlParameterSource("now", now).addValue("ids",
                        g.creditIds()));
                if (marked == 0) {
                    return Outcome.NONE; // 已被并发的另一轮提醒
                }
                if (!eligible(g.userId())) {
                    return Outcome.MARKED_ONLY;
                }
                Locale locale = accounts.localeOf(g.userId());
                Object[] args = args(g, locale);
                notifications.sendWithoutPush(g.userId(), NotificationType.PACK_CREDIT_EXPIRING,
                        messages.getMessage("notify.PACK_CREDIT_EXPIRING.title", null, locale),
                        messages.getMessage("notify.PACK_CREDIT_EXPIRING.body", args, locale), DEEP_LINK_TYPE, null);
                return Outcome.SENT;
            });
            return o == null ? Outcome.NONE : o;
        } catch (RuntimeException e) {
            log.warn("entitlement expiry reminder group failed userId={} family={} cause={}", g.userId(), g.family(),
                    e.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }

    /** 收件人可收：存在、未注销、无注销受理、非虚拟号。 */
    private boolean eligible(long userId) {
        User u = accounts.findUserById(userId).orElse(null);
        return u != null && u.getDeletedAt() == null && u.getAccountType() != AccountType.VIRTUAL
                && deletions.findByUserId(userId).isEmpty();
    }

    /** 文案参数 {0} 数量 / {1} 类型名 / {2} WIB 日期（{@code d MMM yyyy}，按 locale 月份缩写）。 */
    Object[] args(Group g, Locale locale) {
        String typeName = messages.getMessage("notify.packCredit.type." + g.family().name(), null, locale);
        String date = g.date().format(DateTimeFormatter.ofPattern("d MMM yyyy", locale));
        return new Object[] {String.valueOf(g.count()), typeName, date};
    }
}
