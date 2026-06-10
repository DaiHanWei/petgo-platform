package com.petgo.notify.service;

import com.petgo.notify.domain.Notification;
import com.petgo.notify.dto.NotificationItem;
import com.petgo.notify.dto.NotificationPage;
import com.petgo.notify.repository.NotificationRepository;
import com.petgo.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知中心读取/已读/角标（Story 6.6）。读自己 6.1 建的 {@code notifications} 表与 Redis 角标键，
 * <b>不跨模块 join</b>；对外只暴露 token + deepLinkType（不返回顺序 id/target_ref）。
 */
@Service
public class NotificationCenterService {

    private final NotificationRepository repo;
    private final StringRedisTemplate redis;

    public NotificationCenterService(NotificationRepository repo, StringRedisTemplate redis) {
        this.repo = repo;
        this.redis = redis;
    }

    private static String unreadKey(long userId) {
        return NotificationService.UNREAD_KEY_PREFIX + userId;
    }

    /** 倒序游标分页（cursor=上一页末条 epochMillis，首页 null）。 */
    @Transactional(readOnly = true)
    public NotificationPage list(long userId, String cursor, int limit) {
        Instant before = parseCursor(cursor);
        List<Notification> rows = repo.findByRecipientUserIdAndCreatedAtBeforeOrderByCreatedAtDesc(
                userId, before, PageRequest.of(0, limit + 1));
        boolean hasMore = rows.size() > limit;
        List<Notification> pageRows = hasMore ? rows.subList(0, limit) : rows;
        List<NotificationItem> items = pageRows.stream().map(NotificationItem::from).toList();
        String nextCursor = hasMore && !pageRows.isEmpty()
                ? String.valueOf(pageRows.get(pageRows.size() - 1).getCreatedAt().toEpochMilli())
                : null;
        return new NotificationPage(items, nextCursor, hasMore);
    }

    /** 未读角标：读 Redis；缺值按库回算并回填（容错，不依赖角标键永不丢）。 */
    @Transactional(readOnly = true)
    public long unreadCount(long userId) {
        String v = redis.opsForValue().get(unreadKey(userId));
        if (v != null) {
            try {
                return Math.max(0, Long.parseLong(v));
            } catch (NumberFormatException ignored) {
                // 落到回算
            }
        }
        long recomputed = repo.countByRecipientUserIdAndReadIsFalse(userId);
        redis.opsForValue().set(unreadKey(userId), String.valueOf(recomputed));
        return recomputed;
    }

    /** 标记单条已读（token 定位，仅本人，否则 404 防枚举）+ 角标递减（不低于 0）。 */
    @Transactional
    public void markRead(long userId, String token) {
        Notification n = repo.findByDeepLinkTokenAndRecipientUserId(token, userId)
                .orElseThrow(() -> AppException.notFound("通知不存在"));
        if (!n.isRead()) {
            n.markRead();
            repo.save(n);
            decrementBadge(userId);
        }
    }

    /** 全部标记已读 + 角标清零。 */
    @Transactional
    public void markAllRead(long userId) {
        List<Notification> unread = repo.findByRecipientUserIdAndReadIsFalse(userId);
        for (Notification n : unread) {
            n.markRead();
        }
        repo.saveAll(unread);
        redis.opsForValue().set(unreadKey(userId), "0");
    }

    private void decrementBadge(long userId) {
        Long after = redis.opsForValue().decrement(unreadKey(userId));
        if (after != null && after < 0) {
            redis.opsForValue().set(unreadKey(userId), "0");
        }
    }

    private static Instant parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Instant.now().plusSeconds(60); // 首页：取「现在之前」全部（留余量含刚写入）
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(cursor));
        } catch (NumberFormatException e) {
            return Instant.now().plusSeconds(60);
        }
    }
}
