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

    private final NotificationRepository repo;
    private final StringRedisTemplate redis;
    private final NotificationPusher pusher;
    private final SecureRandom random = new SecureRandom();

    public NotificationService(NotificationRepository repo, StringRedisTemplate redis,
            NotificationPusher pusher) {
        this.repo = repo;
        this.redis = redis;
        this.pusher = pusher;
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
        String token = generateToken();
        Notification saved = repo.save(Notification.of(
                recipientUserId, type, title, body, deepLinkType, token, targetRef));
        // 未读角标自增（用户侧通知中心 6.6）。bug 20260625-088：Redis 抖动/不可用**不得**回滚通知落库
        // 或阻断推送——角标是派生数据，稍后由通知中心首页 / unreadCount 回库自愈。
        bumpUnreadBadge(recipientUserId);
        // 离线推送异步投递（失败不阻塞）。
        pusher.pushToUser(recipientUserId, title, body, deepLinkType, token);
        return saved;
    }

    /**
     * 推送给兽医（Story 6.2 新问诊请求）。兽医为独立角色（v_{vetId}）；V1 兽医侧无 6.6 通知中心，
     * 故仅离线推送 + 工作台深链，不写用户通知中心行 / 不增用户角标。
     */
    public void sendToVet(long vetId, NotificationType type, String title, String body, String deepLinkType) {
        pusher.pushToVet(vetId, title, body, deepLinkType, null);
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

    private String generateToken() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(BASE62[random.nextInt(BASE62.length)]);
        }
        return sb.toString();
    }
}
