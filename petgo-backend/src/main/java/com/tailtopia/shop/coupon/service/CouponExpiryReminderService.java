package com.tailtopia.shop.coupon.service;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.preference.PushPreferenceService;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shop.coupon.repository.CouponExpiryNotifyLogDao;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 券快过期提醒 · 单用户处理（v1.4.2 shop Story 2.3 AC5.5 ~ AC5.9 · D-22 · I8）。调度见 {@link CouponExpiryReminderJob}。
 *
 * <ol>
 *   <li><b>逐用户独立事务</b>（REQUIRES_NEW）：先占今天的提醒日志行（主键冲突 = 今天已提醒 → 返回）→
 *       {@link CouponLedgerService#markExpiryNotified} 条件置位（每张券只一次）→ 置位 0 张则整笔回滚（日志行一并撤销）→
 *       日志行张数改为实际张数；</li>
 *   <li><b>提交之后</b>（事务外）发一条 {@code COUPON_EXPIRING}：按收件人 locale 渲染 title / body 落库，推送由
 *       {@code sendWithCopy} 同键同参渲染；关了「促销与活动」/ 虚拟号由 {@code sendWithCopy} 自行跳过推送；</li>
 *   <li>服务端埋点 {@code toko_coupon_expiry_push_sent} 只在<b>确实推送</b>时上报。</li>
 * </ol>
 * 发送 / 埋点失败只 WARN（userId + 异常类名），不重推（at-most-once）。🔒 日志不记券 token、券名、手机号。
 */
@Service
public class CouponExpiryReminderService {

    private static final Logger log = LoggerFactory.getLogger(CouponExpiryReminderService.class);

    /** 单用户处理结果。 */
    public enum Outcome {
        /** 今天已提醒过 / 没有可置位的券：什么都没做。 */
        SKIPPED,
        /** 已置位但账号无效（注销 / 封号）：不发通知、不埋点。 */
        MARKED_ONLY,
        /** 已置位并写入通知（是否推送由偏好 / 虚拟号决定）。 */
        SENT,
        /** 已置位但发送失败（不重发）。 */
        FAILED
    }

    private final CouponLedgerService ledger;
    private final CouponExpiryNotifyLogDao logDao;
    private final NotificationService notifications;
    private final AccountQueryService accounts;
    private final PushPreferenceService prefs;
    private final AnalyticsClient analytics;
    private final CouponExpiryCopy copy;
    private final TransactionOperations newTx;

    @Autowired
    public CouponExpiryReminderService(CouponLedgerService ledger, CouponExpiryNotifyLogDao logDao,
            NotificationService notifications, AccountQueryService accounts, PushPreferenceService prefs,
            AnalyticsClient analytics, CouponExpiryCopy copy, PlatformTransactionManager txManager) {
        this(ledger, logDao, notifications, accounts, prefs, analytics, copy, requiresNew(txManager));
    }

    CouponExpiryReminderService(CouponLedgerService ledger, CouponExpiryNotifyLogDao logDao,
            NotificationService notifications, AccountQueryService accounts, PushPreferenceService prefs,
            AnalyticsClient analytics, CouponExpiryCopy copy, TransactionOperations newTx) {
        this.ledger = ledger;
        this.logDao = logDao;
        this.notifications = notifications;
        this.accounts = accounts;
        this.prefs = prefs;
        this.analytics = analytics;
        this.copy = copy;
        this.newTx = newTx;
    }

    private static TransactionOperations requiresNew(PlatformTransactionManager txManager) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    /**
     * 处理一个用户：窗口 {@code (now, until]}；「今天」= {@code now} 的 WIB 日期。
     * 置位事务失败会抛出（调用方计失败、继续下一人）；发送阶段异常不抛出。
     */
    public Outcome remindUser(long userId, Instant now, Instant until) {
        LocalDate wibToday = CouponExpiryCopy.wibDate(now);
        List<Instant> marked = newTx.execute(status -> {
            if (logDao.insertIfAbsent(userId, wibToday) == 0) {
                return List.<Instant>of(); // 今天已提醒过：同一用户同一 WIB 日只 1 条
            }
            List<Instant> m = ledger.markExpiryNotified(userId, now, until);
            if (m.isEmpty()) {
                status.setRollbackOnly(); // 不留空日志行
                return List.<Instant>of();
            }
            logDao.updateCount(userId, wibToday, m.size());
            return m;
        });
        if (marked == null || marked.isEmpty()) {
            return Outcome.SKIPPED;
        }
        if (!accounts.isActiveAccount(userId)) {
            return Outcome.MARKED_ONLY; // 已置位（避免每天重复扫到），不发通知、不埋点
        }
        int n = marked.size();
        Instant earliest = Collections.min(marked);
        try {
            boolean willPush = pushAllowed(userId) && !accounts.isVirtual(userId);
            Locale locale = accounts.localeOf(userId);
            Object[] args = copy.args(n, earliest, locale);
            notifications.sendWithCopy(userId, NotificationType.COUPON_EXPIRING, CouponExpiryCopy.COPY_KEY, args,
                    copy.render("title", args, locale), copy.render("body", args, locale), String.valueOf(n));
            if (willPush) {
                analytics.captureForUser(userId, "toko_coupon_expiry_push_sent",
                        Map.of("coupon_count", n));
            }
            return Outcome.SENT;
        } catch (RuntimeException e) {
            log.warn("券快过期提醒发送失败（不重发）userId={} cause={}", userId, e.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }

    private boolean pushAllowed(long userId) {
        try {
            return prefs.isEnabled(userId, NotificationType.COUPON_EXPIRING);
        } catch (RuntimeException e) {
            return false; // 判不出就不报「已推送」，宁少勿虚高
        }
    }
}
