package com.tailtopia.notify.service;

import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.repository.NotificationRepository;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统一推送出口（Story 6.1）。所有业务推送（6.2/6.3 经领域事件订阅）汇聚于此：
 * ① 写一行 {@code notifications}（供 6.6 通知中心） ② Redis 未读角标 {@code notify:unread:{userId}} 自增
 * ③ 经 {@code shared/im} 离线通道异步下发（携 {@code type + deepLinkToken}）。
 *
 * <p>护栏：深链用不可枚举 token（绝不顺序 id）；不引入 MQ/独立 TPNS；日志不落 PII/健康/token。
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** Redis 未读角标计数键前缀（仅角标用途，不当通用缓存）。 */
    public static final String UNREAD_KEY_PREFIX = "notify:unread:";

    private static final char[] BASE62 =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    /** 兽医推送默认语言（V1 兽医无语言偏好字段，统一印尼语）。 */
    private static final java.util.Locale INDONESIAN = java.util.Locale.forLanguageTag("id");

    private final NotificationRepository repo;
    private final StringRedisTemplate redis;
    private final NotificationPusher pusher;
    private final org.springframework.context.MessageSource messageSource;
    private final com.tailtopia.auth.service.AccountQueryService accountQuery;
    private final SecureRandom random = new SecureRandom();

    public NotificationService(NotificationRepository repo, StringRedisTemplate redis,
            NotificationPusher pusher, org.springframework.context.MessageSource messageSource,
            com.tailtopia.auth.service.AccountQueryService accountQuery) {
        this.repo = repo;
        this.redis = redis;
        this.pusher = pusher;
        this.messageSource = messageSource;
        this.accountQuery = accountQuery;
    }

    /**
     * 按 type + 收件人语言渲染 push 文案（bug 20260625-105）。键 {@code notify.<TYPE>.title/body}；
     * 缺键回退调用方传入的原文案。仅用于系统推送——站内通知中心由 App 端按 type 自行本地化。
     */
    private String pushText(NotificationType type, String suffix, java.util.Locale locale, String fallback) {
        return messageSource.getMessage("notify." + type.name() + "." + suffix, null, fallback, locale);
    }

    /**
     * 发送通知。{@code targetRef} 为内部回查目标资源标识（不外泄）；自动生成不可枚举 {@code deepLinkToken}。
     * 返回落库的通知（含 token）。
     *
     * <p><b>REQUIRES_NEW 必需</b>：内容/问诊/审核订阅者是同步的 {@code @TransactionalEventListener}
     * （默认 AFTER_COMMIT 阶段），此时触发事务已提交但同步资源仍绑定。若用默认 REQUIRED，本方法会
     * 「加入」那个已提交的事务 → {@code repo.save} 的 INSERT 永不提交而被静默丢弃（角标 Redis 自增/推送
     * 却照常执行）→ 表象为「首页角标涨了但通知中心为空」。里程碑订阅者因带 {@code @Async}（另起线程、
     * 无环境事务）侥幸落库，其余四类全丢。REQUIRES_NEW 强制挂起并新开独立事务，保证通知真正提交。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification send(long recipientUserId, NotificationType type, String title, String body,
            String deepLinkType, String targetRef) {
        return write(recipientUserId, type, title, body, deepLinkType, targetRef, true);
    }

    /**
     * 只写通知中心、**不发系统推送**（V1.1.6 Story 6.1 · FR-76 / AD-13）。
     *
     * <p>用于 S/M 级里程碑达成：数量比 L 级多得多，推送会变成打扰；但仍要进通知中心留痕，
     * 好让"别人的互动触发了里程碑、本人当时不在现场"这件事被告知到。
     *
     * <p>🛡 <b>刻意与 {@link #send} 共用同一段落库 + 角标逻辑</b>，只切掉推送那一步 ——
     * 未读角标自增就在那段里。另写一套"只落库"的逻辑就等于把 AC「必须让角标递增」那条护栏丢了：
     * 通知进了中心而铃铛不动，用户仍需主动去翻，本 FR 直接失效。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification sendWithoutPush(long recipientUserId, NotificationType type, String title,
            String body, String deepLinkType, String targetRef) {
        return write(recipientUserId, type, title, body, deepLinkType, targetRef, false);
    }

    private Notification write(long recipientUserId, NotificationType type, String title, String body,
            String deepLinkType, String targetRef, boolean push) {
        String token = generateToken();
        Notification saved = repo.save(Notification.of(
                recipientUserId, type, title, body, deepLinkType, token, targetRef));
        // 未读角标自增（用户侧通知中心 6.6）。bug 20260625-088：Redis 抖动/不可用**不得**回滚通知落库
        // 或阻断推送——角标是派生数据，稍后由通知中心首页 / unreadCount 回库自愈。
        bumpUnreadBadge(recipientUserId);
        if (!push) {
            return saved; // 只留痕、不打扰（Story 6.1）。角标已在上一行涨过。
        }
        // 离线推送异步投递（失败不阻塞）。push 文案按收件人语言渲染（bug 20260625-105）；
        // 站内通知中心不用这里的文本（App 端按 type 自行本地化），故落库 title/body 保持不变。
        java.util.Locale locale = accountQuery.localeOf(recipientUserId);
        pusher.pushToUser(recipientUserId,
                pushText(type, "title", locale, title), pushText(type, "body", locale, body),
                deepLinkType, token, targetRef);
        return saved;
    }

    /**
     * 推送给兽医（Story 6.2 新问诊请求）。兽医为独立角色（v_{vetId}）；V1 兽医侧无 6.6 通知中心，
     * 故仅离线推送 + 工作台深链，不写用户通知中心行 / 不增用户角标。
     */
    public void sendToVet(long vetId, NotificationType type, String title, String body, String deepLinkType) {
        // 兽医侧 V1 无语言偏好，统一印尼语渲染（bug 20260625-105）。
        pusher.pushToVet(vetId,
                pushText(type, "title", INDONESIAN, title), pushText(type, "body", INDONESIAN, body),
                deepLinkType, null, null);
    }

    /**
     * 未读角标 +1，容错。bug 20260625-088：③ Redis 异常吞掉不冒泡（否则整个 {@code send} 事务回滚→
     * 通知根本没落库、推送也发不出）；④ INCR 命中被 evict/重启清空的缺失键会从 0 起算导致少算——
     * 返回 1 时回库用真实未读数纠正（同事务已含刚落库这条），避免 evict 后角标长期偏小。
     */
    private void bumpUnreadBadge(long recipientUserId) {
        try {
            String key = UNREAD_KEY_PREFIX + recipientUserId;
            Long newCount = redis.opsForValue().increment(key);
            if (newCount != null && newCount == 1L) {
                long dbUnread = repo.countByRecipientUserIdAndReadIsFalse(recipientUserId);
                if (dbUnread > 1) {
                    redis.opsForValue().set(key, String.valueOf(dbUnread));
                }
            }
        } catch (RuntimeException e) {
            log.warn("未读角标自增失败（不阻断通知落库/推送）recipientUserId={} cause={}",
                    recipientUserId, e.getClass().getSimpleName());
        }
    }

    /**
     * 发送「文案带参数」的通知（留存手册抓手 1）。与 {@link #send} 的唯一差别：push 文案的 i18n 键
     * 由调用方显式给出（{@code copyKey}，如 {@code LIFECYCLE_D1.RECORD}），且支持 {@code {0}} 占位符注入。
     *
     * <p><b>为什么必须有这个重载</b>：{@link #send} 用 {@code notify.<TYPE>.body} 取文案且 {@code args=null}，
     * 一个 type 只能有一句<b>静态</b>话。生日推送就栽在这上面——dispatcher 明明拼好了「Mochi 明天满 3 岁」，
     * 却被静态键 {@code notify.PET_BIRTHDAY.body=Hari ini ulang tahun anabulmu}（"今天是你家宠物生日"）
     * 整句覆盖：宠物名没了，"明天"还被说成"今天"。而留存手册的铁律恰恰是<b>文案必须带宠物名</b>
     * ——「记录 Mochi 的一个瞬间」和「回来看看」是两个转化率量级。
     *
     * <p>⚠️ {@code args != null} 时 Spring 会走 {@code MessageFormat}，此时 i18n 串里的单引号必须写成
     * {@code ''}，否则会被当成引用块吞掉后面的占位符。新增串务必自查。
     *
     * @param copyKey 文案键后缀，完整键为 {@code notify.<copyKey>.title/body}。
     * @param args    占位符实参（如宠物名）；{@code null} 表示无参（退化为静态取串）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification sendWithCopy(long recipientUserId, NotificationType type, String copyKey,
            Object[] args, String fallbackTitle, String fallbackBody, String targetRef) {
        String token = generateToken();
        // 落库 title/body 用兜底原文（通知中心由 App 端按 type + variant 自行本地化，不读这两列）。
        Notification saved = repo.save(Notification.of(
                recipientUserId, type, fallbackTitle, fallbackBody, type.name(), token, targetRef));
        bumpUnreadBadge(recipientUserId);
        java.util.Locale locale = accountQuery.localeOf(recipientUserId);
        pusher.pushToUser(recipientUserId,
                formatCopy(copyKey, "title", args, locale, fallbackTitle),
                formatCopy(copyKey, "body", args, locale, fallbackBody),
                type.name(), token, targetRef);
        return saved;
    }

    /** 按 {@code notify.<copyKey>.<suffix>} 取串并注入 {@code args}；缺键回退 {@code fallback}。 */
    private String formatCopy(String copyKey, String suffix, Object[] args, java.util.Locale locale,
            String fallback) {
        return messageSource.getMessage("notify." + copyKey + "." + suffix, args, fallback, locale);
    }

    private String generateToken() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(BASE62[random.nextInt(BASE62.length)]);
        }
        return sb.toString();
    }
}
