package com.tailtopia.notify.service;

import com.tailtopia.auth.dto.ActorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.CommentQueryService;
import com.tailtopia.content.service.CommentQueryService.NotificationCommentSnapshot;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationActor;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.dto.NotificationItem;
import com.tailtopia.notify.dto.NotificationPage;
import com.tailtopia.notify.repository.NotificationActorRepository;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.notify.service.InteractionCopyRenderer.ActorSummary;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.paging.KeysetCursor;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.FollowState;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知中心读取/已读/角标（Story 6.6）。读自己 6.1 建的 {@code notifications} 表与 Redis 角标键，
 * <b>不跨模块 join</b>；对外只暴露 token + deepLinkType（不返回顺序 id/target_ref）。
 *
 * <h2>V1.3.4 follow · Story 3.3：互动条目现拼「谁 + 做了什么」（AD-11）</h2>
 * 四种互动类型且有触发人行的条目，按<b>接收方账号语言</b>（T-38）用 {@link InteractionCopyRenderer}（与推送同一模板）现拼
 * {@code title / body}，并带最新触发人与人数。一页所需外部取数<b>各一次</b>（NFR3 / AD-16）：① 触发人行
 * ② 触发人投影（昵称 + token + 是否有效）③ 接收方隐藏集合 ④ 评论快照（有评论条目才查）⑤ 关注关系（有新粉丝条目才查）；
 * 本页没有候选则一个都不调。跨模块只走对方的 service / 读端口，不写跨模块 SQL。
 *
 * <p>⚠️ T-36：触发人<b>全部</b>被过滤（注销 / 封号 / 被我隐藏）的条目<b>从列表隐藏</b>，未读角标同口径不计
 * （首页校准与缺键回算都扣掉它们）。分页游标仍按原始行推进，被隐藏的条目只会让某一页少几条。
 * 合并会把 {@code created_at} 刷到当前：翻页期间被合并的那条会跳回首页，本次翻页看不到，重进即可。
 */
@Service
public class NotificationCenterService {

    private static final Logger log = LoggerFactory.getLogger(NotificationCenterService.class);

    private final NotificationRepository repo;
    private final StringRedisTemplate redis;
    private final NotificationActorRepository actorRepo;
    private final AccountQueryService accounts;
    private final UserHideRelationReader hideRelations;
    private final CommentQueryService comments;
    private final FollowReader followReader;
    private final InteractionCopyRenderer copy;

    public NotificationCenterService(NotificationRepository repo, StringRedisTemplate redis,
            NotificationActorRepository actorRepo, AccountQueryService accounts,
            UserHideRelationReader hideRelations, CommentQueryService comments, FollowReader followReader,
            InteractionCopyRenderer copy) {
        this.repo = repo;
        this.redis = redis;
        this.actorRepo = actorRepo;
        this.accounts = accounts;
        this.hideRelations = hideRelations;
        this.comments = comments;
        this.followReader = followReader;
        this.copy = copy;
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
        List<NotificationItem> items = renderPage(userId, pageRows);
        Notification last = pageRows.isEmpty() ? null : pageRows.get(pageRows.size() - 1);
        String nextCursor = hasMore && last != null
                ? new KeysetCursor(last.getCreatedAt(), last.getId()).encode()
                : null;
        // 打开通知中心（首页）时以 DB 真实未读数校准 Redis 角标，自愈计数漂移
        // （如计数器残留致角标>0 但列表空，或行被清而计数未减）。仅校准计数，不改已读态。
        if (cursor == null) {
            long actualUnread = effectiveUnreadCount(userId);
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
        long recomputed = effectiveUnreadCount(userId);
        redis.opsForValue().set(unreadKey(userId), String.valueOf(recomputed));
        return recomputed;
    }

    // ---------- Story 3.3：互动条目现拼 ----------

    /** 一页条目：候选（四种互动类型）批量现拼；其余原样；触发人全被过滤的条目隐藏（T-36）。 */
    private List<NotificationItem> renderPage(long userId, List<Notification> pageRows) {
        List<Long> candidateIds = pageRows.stream()
                .filter(n -> NotificationService.INTERACTION_TYPES.contains(n.getType()))
                .map(Notification::getId).toList();
        if (candidateIds.isEmpty()) {
            return pageRows.stream().map(NotificationItem::from).toList();
        }
        // ① ② ③
        Map<Long, List<Long>> actorsByNotification = actorsOf(candidateIds);
        Map<Long, Optional<ActorSummary>> summaries = summarize(userId, actorsByNotification);
        // ④ 评论快照：只对有可显示触发人的评论条目
        Set<Long> commentIds = new LinkedHashSet<>();
        // ⑤ 新粉丝关系：只对有可显示触发人的新粉丝条目
        Set<Long> followerIds = new LinkedHashSet<>();
        for (Notification n : pageRows) {
            Optional<ActorSummary> s = summaries.get(n.getId());
            if (s == null || s.isEmpty()) {
                continue;
            }
            if (n.getType() == NotificationType.CONTENT_COMMENTED && n.getCommentId() != null) {
                commentIds.add(n.getCommentId());
            } else if (n.getType() == NotificationType.USER_FOLLOWED) {
                followerIds.add(s.get().latestActorId());
            }
        }
        Map<Long, NotificationCommentSnapshot> snapshots = commentIds.isEmpty() ? Map.of()
                : comments.notificationSnapshots(commentIds);
        Map<Long, FollowState> states = followerIds.isEmpty() ? Map.of()
                : followReader.followStates(userId, followerIds);
        Locale locale = summaries.values().stream().anyMatch(Optional::isPresent)
                ? accounts.localeOf(userId) : null;

        List<NotificationItem> items = new ArrayList<>(pageRows.size());
        for (Notification n : pageRows) {
            Optional<ActorSummary> s = summaries.get(n.getId());
            if (s == null) {
                items.add(NotificationItem.from(n)); // 非互动类型，或上线前的存量（无触发人行）
            } else if (s.isPresent()) {
                items.add(render(n, s.get(), locale, snapshots, states));
            }
            // s.isEmpty()：触发人全被过滤 → 从列表隐藏（T-36）
        }
        return items;
    }

    private NotificationItem render(Notification n, ActorSummary s, Locale locale,
            Map<Long, NotificationCommentSnapshot> snapshots, Map<Long, FollowState> states) {
        String title = copy.title(n.getType(), n.getVariant(), s, locale);
        String body = null;
        Boolean iFollow = null;
        Boolean followsMe = null;
        if (n.getType() == NotificationType.CONTENT_COMMENTED && n.getCommentId() != null) {
            NotificationCommentSnapshot c = snapshots.get(n.getCommentId());
            body = c == null || c.gone() ? copy.commentDeleted(locale) : c.body();
        } else if (n.getType() == NotificationType.USER_FOLLOWED) {
            FollowState f = states.getOrDefault(s.latestActorId(), FollowState.NONE);
            iFollow = f.iFollow();
            followsMe = f.followsMe();
        }
        return NotificationItem.rendered(n, title, body, s.latestActorId(), s.latestNickname(), s.latestToken(),
                s.total(), iFollow, followsMe);
    }

    /** ① 触发人行一次取全，按通知分组（每组最新在前）。没有触发人行的通知不出现在结果里。 */
    private Map<Long, List<Long>> actorsOf(List<Long> notificationIds) {
        Map<Long, List<Long>> out = new HashMap<>();
        for (NotificationActor a : actorRepo.findByNotificationIds(notificationIds)) {
            out.computeIfAbsent(a.getId().getNotificationId(), k -> new ArrayList<>()).add(a.getId().getActorUserId());
        }
        return out;
    }

    /** ② ③ 各一次批量，再逐条纯函数过滤（与推送同一规则）。无触发人行 → 空 map。 */
    private Map<Long, Optional<ActorSummary>> summarize(long userId, Map<Long, List<Long>> actorsByNotification) {
        if (actorsByNotification.isEmpty()) {
            return Map.of();
        }
        Set<Long> allActors = new LinkedHashSet<>();
        actorsByNotification.values().forEach(allActors::addAll);
        Map<Long, ActorView> views = accounts.findActorViews(allActors);
        Set<Long> hidden = hideRelations.hiddenByHolder(userId, allActors);
        Map<Long, Optional<ActorSummary>> out = new HashMap<>();
        actorsByNotification.forEach((id, actors) ->
                out.put(id, InteractionCopyRenderer.summarize(actors, views, hidden)));
        return out;
    }

    /**
     * 未读角标口径（⚠️ T-36）：库里未读数 − 「触发人全部被过滤、因而列表里看不见」的未读互动条目数。
     * 只在首页校准与缺键回算时调用（日常读角标仍走 Redis）。
     */
    private long effectiveUnreadCount(long userId) {
        long unread = repo.countByRecipientUserIdAndReadIsFalse(userId);
        List<Long> interactionIds = repo.findUnreadInteractionIdsWithActors(userId);
        if (interactionIds.isEmpty()) {
            return unread;
        }
        long invisible = summarize(userId, actorsOf(interactionIds)).values().stream()
                .filter(Optional::isEmpty).count();
        return Math.max(0, unread - invisible);
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
