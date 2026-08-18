package com.tailtopia.notify.service;

import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.dto.NotificationItem;
import com.tailtopia.notify.dto.NotificationPage;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.shared.error.AppException;
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

    /**
     * 倒序游标分页。
     *
     * <p>游标格式 {@code "<epochMicros>_<id>"}（首页 null）。对客户端是<b>不透明串</b>：
     * App 只把 {@code nextCursor} 原样回传，不解析（已核对 Flutter 侧 `NotificationPage`）。
     */
    @Transactional(readOnly = true)
    public NotificationPage list(long userId, String cursor, int limit) {
        Cursor c = parseCursor(cursor);
        List<Notification> rows = repo.findPageBefore(
                userId, c.ts(), c.id(), PageRequest.of(0, limit + 1));
        boolean hasMore = rows.size() > limit;
        List<Notification> pageRows = hasMore ? rows.subList(0, limit) : rows;
        List<NotificationItem> items = pageRows.stream().map(NotificationItem::from).toList();
        String nextCursor = hasMore && !pageRows.isEmpty()
                ? encodeCursor(pageRows.get(pageRows.size() - 1))
                : null;
        // 打开通知中心（首页）时以 DB 真实未读数校准 Redis 角标，自愈计数漂移
        // （如计数器残留致角标>0 但列表空，或行被清而计数未减）。仅校准计数，不改已读态。
        if (cursor == null) {
            long actualUnread = repo.countByRecipientUserIdAndReadIsFalse(userId);
            redis.opsForValue().set(unreadKey(userId), String.valueOf(actualUnread));
        }
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

    // ---------- 游标 ----------

    /**
     * 复合游标：{@code (createdAt, id)}。
     *
     * <p>🔴 <b>只有 {@code createdAt} 是不够的</b>：同一微秒内可以有多条通知（批量触达就是），
     * 而分页必须有<b>全序唯一</b>的锚点，否则边界处的记录要么被跳过、要么重复。
     */
    private record Cursor(Instant ts, long id) {
    }

    /** 首页哨兵：取「现在之前」全部（留余量含刚写入）。 */
    private static Cursor firstPage() {
        return new Cursor(Instant.now().plusSeconds(60), Long.MAX_VALUE);
    }

    /**
     * 编码为 {@code "<epochMicros>_<id>"}。
     *
     * <p>⚠️ <b>按微秒取，不是毫秒</b>：Postgres {@code timestamptz} 的精度就是微秒，
     * 截到毫秒会让下一页的 {@code createdAt = :beforeTs} 恒不成立，
     * 复合游标就退化回原来那个「整批跳过」的 bug。
     */
    private static String encodeCursor(Notification last) {
        Instant at = last.getCreatedAt();
        long micros = at.getEpochSecond() * 1_000_000L + at.getNano() / 1_000L;
        return micros + "_" + last.getId();
    }

    private static Cursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return firstPage();
        }
        int sep = cursor.indexOf('_');
        try {
            if (sep < 0) {
                // 过渡兼容：老客户端手上还捏着旧格式（纯 epochMillis）的游标。
                // 用 Long.MIN_VALUE 让「同刻」分支恒不命中 → 行为与老实现逐字一致（仍会漏，
                // 但不会因为换了格式而报错或错位）。老客户端翻完这一轮就没有旧游标了。
                return new Cursor(Instant.ofEpochMilli(Long.parseLong(cursor)), Long.MIN_VALUE);
            }
            long micros = Long.parseLong(cursor.substring(0, sep));
            long id = Long.parseLong(cursor.substring(sep + 1));
            return new Cursor(
                    Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                            Math.floorMod(micros, 1_000_000L) * 1_000L),
                    id);
        } catch (NumberFormatException | ArithmeticException e) {
            // 🔴 游标是客户端传来的，坏值不能 500 —— 退化成首页，用户至少看得到最新的
            return firstPage();
        }
    }
}
