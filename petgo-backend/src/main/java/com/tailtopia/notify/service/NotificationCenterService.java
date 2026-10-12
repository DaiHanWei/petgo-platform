package com.tailtopia.notify.service;

import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.dto.NotificationItem;
import com.tailtopia.notify.dto.NotificationPage;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.ops.target.OpsTargetView;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.paging.KeysetCursor;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知中心读取/已读/角标（Story 6.6）。读自己 6.1 建的 {@code notifications} 表与 Redis 角标键，
 * <b>不跨模块 join</b>；对外只暴露 token + deepLinkType（不返回顺序 id/target_ref）。
 */
@Service
public class NotificationCenterService {

    private static final Logger log = LoggerFactory.getLogger(NotificationCenterService.class);

    private final NotificationRepository repo;
    private final StringRedisTemplate redis;
    private final OpsNotificationCampaignRepository campaigns;

    public NotificationCenterService(NotificationRepository repo, StringRedisTemplate redis,
            OpsNotificationCampaignRepository campaigns) {
        this.repo = repo;
        this.redis = redis;
        this.campaigns = campaigns;
    }

    private static String unreadKey(long userId) {
        return NotificationService.UNREAD_KEY_PREFIX + userId;
    }

    /**
     * 倒序游标分页。
     *
     * <p>游标是 {@link KeysetCursor}（base64url 的 {@code (createdAt, id)}，首页 null）。
     * 对客户端是<b>不透明串</b>：App 只把 {@code nextCursor} 原样回传，不解析
     * （已核对 Flutter 侧 `NotificationPage`）。
     */
    @Transactional(readOnly = true)
    public NotificationPage list(long userId, String cursor, int limit) {
        KeysetCursor c = parseCursor(cursor);
        List<Notification> rows = repo.findPageBefore(
                userId, c.createdAt(), c.id(), PageRequest.of(0, limit + 1));
        boolean hasMore = rows.size() > limit;
        List<Notification> pageRows = hasMore ? rows.subList(0, limit) : rows;
        List<NotificationItem> items = toItems(pageRows);
        Notification last = pageRows.isEmpty() ? null : pageRows.get(pageRows.size() - 1);
        String nextCursor = hasMore && last != null
                ? new KeysetCursor(last.getCreatedAt(), last.getId()).encode()
                : null;
        // 打开通知中心（首页）时以 DB 真实未读数校准 Redis 角标，自愈计数漂移
        // （如计数器残留致角标>0 但列表空，或行被清而计数未减）。仅校准计数，不改已读态。
        if (cursor == null) {
            long actualUnread = repo.countByRecipientUserIdAndReadIsFalse(userId);
            // 🔎 漂移可观测（2026-08-19 加）：校准前后不一致说明角标确实漂了。
            // 线上反馈过「铃铛有数字、点进去没有新消息」，但代码里已知的四条成因
            // （INSERT 被静默丢弃 / 角标不自愈 / 前端刷太早 / 失败画成空态）都已修，
            // 复现不出来。此处如实记下每一次真实漂移，把「查不出」变成「下次有据可查」。
            // 只记内部 userId 与两个计数，不含任何 PII / 通知内容。
            String beforeRaw = redis.opsForValue().get(unreadKey(userId));
            if (beforeRaw != null && !beforeRaw.equals(String.valueOf(actualUnread))) {
                log.warn("未读角标与库不一致，已按库校准 userId={} badgeWas={} actualUnread={} listSize={}",
                        userId, beforeRaw, actualUnread, items.size());
            }
            redis.opsForValue().set(unreadKey(userId), String.valueOf(actualUnread));
        }
        return new NotificationPage(items, nextCursor, hasMore);
    }

    /**
     * 行 → 列表项。运营通知（{@code OPS_ANNOUNCEMENT}）按 targetRef（活动 publicToken）<b>一页一次</b>批量回查活动，
     * 附带 {@code opsTarget}；targetRef 为 null 或查不到活动（理论上不会发生，活动不提供删除）给 {@code NONE}。其它类型 opsTarget = null。
     */
    private List<NotificationItem> toItems(List<Notification> pageRows) {
        Set<String> opsTokens = pageRows.stream()
                .filter(n -> n.getType() == NotificationType.OPS_ANNOUNCEMENT && n.getTargetRef() != null)
                .map(Notification::getTargetRef)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, OpsTargetView> targets = opsTokens.isEmpty()
                ? Map.of()
                : campaigns.findByPublicTokenIn(opsTokens).stream().collect(Collectors.toMap(
                        OpsNotificationCampaign::getPublicToken,
                        c -> OpsTargetView.of(c.getTargetType(), c.getTargetValue()),
                        (a, b) -> a));
        OpsTargetView none = OpsTargetView.of(OpsTargetType.NONE, null);
        return pageRows.stream()
                .map(n -> n.getType() == NotificationType.OPS_ANNOUNCEMENT
                        // Map.of() 不接受 null 键：targetRef 为 null 先判，直接给 NONE
                        ? NotificationItem.from(n, n.getTargetRef() == null
                                ? none
                                : targets.getOrDefault(n.getTargetRef(), none))
                        : NotificationItem.from(n))
                .toList();
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
     * 解析游标。
     *
     * <p>🔴 坏游标退化成首页而不是报错：游标是客户端传回来的，
     * 让整个通知中心 4xx/5xx 等于把用户锁在门外。
     */
    private static KeysetCursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return KeysetCursor.firstPage();
        }
        KeysetCursor parsed = KeysetCursor.decodeOrNull(cursor);
        if (parsed != null) {
            return parsed;
        }
        // 过渡兼容：老客户端手上还捏着旧格式（纯 epochMillis）的游标。
        // 用 Long.MIN_VALUE 让「同刻」分支恒不命中 → 行为与老实现逐字一致（仍会漏，
        // 但不会因为换了格式而报错或错位）。老客户端翻完这一轮就没有旧游标了。
        try {
            return new KeysetCursor(Instant.ofEpochMilli(Long.parseLong(cursor.trim())),
                    Long.MIN_VALUE);
        } catch (NumberFormatException e) {
            return KeysetCursor.firstPage();
        }
    }
}
