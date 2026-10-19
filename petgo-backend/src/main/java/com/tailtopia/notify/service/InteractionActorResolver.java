package com.tailtopia.notify.service;

import com.tailtopia.auth.dto.ActorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.NotificationActor;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.repository.NotificationActorRepository;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.notify.service.InteractionCopyRenderer.ActorSummary;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 互动通知的触发人批量解析与「有效未读数」（V1.3.4 follow · Story 3.3 · AD-11 / ⚠️ T-36）。
 *
 * <p>通知列表、角标校准、写入时的角标自愈三处共用这<b>一份</b>口径（3.3 code-review F4 / F6）：
 * 一批通知各自的「最新可显示触发人 + 其余人数」由 ① 触发人行 ② 触发人投影 ③ 接收方隐藏集合<b>各一次</b>批量查询算出，
 * 再逐条走纯函数 {@link InteractionCopyRenderer#summarize}（与推送同一规则：注销 / 封号 / 被我隐藏不计）。
 * 触发人全被过滤的条目「不可见」：列表隐藏、未读角标不计。
 *
 * <p>⚠️ 已知限制：一次取全部 actor 行。未读期间持续合并的爆款帖一条通知可挂很多 actor 行、大号未读新粉丝很多时，
 * {@code IN} 列表随之变长（JDBC 绑定参数上限约 3.2 万）；V1 规模可接受，日后成瓶颈再改为库内聚合（需另评审模块边界）。
 */
@Component
public class InteractionActorResolver {

    /** 原生查询用的互动类型名（与 {@link NotificationService#INTERACTION_TYPES} 同一事实源，3.3 code-review F9）。 */
    static final List<String> INTERACTION_TYPE_NAMES = NotificationService.INTERACTION_TYPES.stream()
            .map(NotificationType::name).sorted().toList();

    private final NotificationRepository repo;
    private final NotificationActorRepository actorRepo;
    private final AccountQueryService accounts;
    private final UserHideRelationReader hideRelations;

    public InteractionActorResolver(NotificationRepository repo, NotificationActorRepository actorRepo,
            AccountQueryService accounts, UserHideRelationReader hideRelations) {
        this.repo = repo;
        this.actorRepo = actorRepo;
        this.accounts = accounts;
        this.hideRelations = hideRelations;
    }

    /**
     * 一批通知的触发人摘要。<b>没有触发人行的通知不出现在结果里</b>（上线前的存量 → 调用方原样返回）；
     * 值为空 = 触发人全被过滤（不可见）。空入参不发查询。
     */
    public Map<Long, Optional<ActorSummary>> summarize(long recipientUserId, Collection<Long> notificationIds) {
        if (notificationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> actorsByNotification = new HashMap<>();
        for (NotificationActor a : actorRepo.findByNotificationIds(notificationIds)) {
            actorsByNotification.computeIfAbsent(a.getId().getNotificationId(), k -> new ArrayList<>())
                    .add(a.getId().getActorUserId());
        }
        if (actorsByNotification.isEmpty()) {
            return Map.of();
        }
        Set<Long> allActors = new LinkedHashSet<>();
        actorsByNotification.values().forEach(allActors::addAll);
        Map<Long, ActorView> views = accounts.findActorViews(allActors);
        Set<Long> hidden = hideRelations.hiddenByHolder(recipientUserId, allActors);
        Map<Long, Optional<ActorSummary>> out = new HashMap<>();
        actorsByNotification.forEach((id, actors) ->
                out.put(id, InteractionCopyRenderer.summarize(actors, views, hidden)));
        return out;
    }

    /**
     * 指定触发人并入各条通知的时刻（{@code notification_actors.acted_at}；V1.4.0 gift L2 第二轮：收礼条目按它圈出
     * 「本条通知期间送的礼」）。一次查询；查不到的通知不出现。空入参不发查询。
     *
     * @param actorByNotification 通知 id → 触发人 id
     */
    public Map<Long, java.time.Instant> actedAt(Map<Long, Long> actorByNotification) {
        if (actorByNotification.isEmpty()) {
            return Map.of();
        }
        Map<Long, java.time.Instant> out = new HashMap<>();
        for (NotificationActor a : actorRepo.findByNotificationIds(actorByNotification.keySet())) {
            Long want = actorByNotification.get(a.getId().getNotificationId());
            // 两边都是 Long：必须按数值比（== 是引用比较，id ≥ 128 出了 Long 缓存恒 false → 收礼单人条目拿不到礼物名）
            if (want != null && java.util.Objects.equals(want, a.getId().getActorUserId()) && a.getActedAt() != null) {
                out.put(a.getId().getNotificationId(), a.getActedAt());
            }
        }
        return out;
    }

    /** 某人未读的、带触发人行的互动通知 id（角标口径扣除的候选）。 */
    public List<Long> unreadInteractionIds(long userId) {
        return repo.findUnreadInteractionIdsWithActors(userId, INTERACTION_TYPE_NAMES);
    }

    /** {@code ids} 里不可见（触发人全被过滤）的条数。 */
    public static long invisibleCount(Map<Long, Optional<ActorSummary>> summaries, Collection<Long> ids) {
        return ids.stream().map(summaries::get).filter(s -> s != null && s.isEmpty()).count();
    }

    /** 有效未读数（⚠️ T-36）= 库里未读数 − 不可见的未读互动条目数。 */
    public long effectiveUnreadCount(long userId) {
        long unread = repo.countByRecipientUserIdAndReadIsFalse(userId);
        List<Long> ids = unreadInteractionIds(userId);
        if (ids.isEmpty()) {
            return unread;
        }
        return Math.max(0, unread - invisibleCount(summarize(userId, ids), ids));
    }
}
