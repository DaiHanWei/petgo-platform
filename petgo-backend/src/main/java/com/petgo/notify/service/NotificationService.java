package com.petgo.notify.service;

import com.petgo.notify.domain.Notification;
import com.petgo.notify.domain.NotificationType;
import com.petgo.notify.repository.NotificationRepository;
import java.security.SecureRandom;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
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
     */
    @Transactional
    public Notification send(long recipientUserId, NotificationType type, String title, String body,
            String deepLinkType, String targetRef) {
        String token = generateToken();
        Notification saved = repo.save(Notification.of(
                recipientUserId, type, title, body, deepLinkType, token, targetRef));
        // 未读角标自增。
        redis.opsForValue().increment(UNREAD_KEY_PREFIX + recipientUserId);
        // 离线推送异步投递（失败不阻塞）。
        pusher.push(recipientUserId, title, body, deepLinkType, token);
        return saved;
    }

    private String generateToken() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(BASE62[random.nextInt(BASE62.length)]);
        }
        return sb.toString();
    }
}
