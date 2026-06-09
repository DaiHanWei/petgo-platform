package com.petgo.profile.service;

import com.petgo.content.domain.ContentType;
import com.petgo.content.event.ContentCommentedEvent;
import com.petgo.content.event.ContentLikedEvent;
import com.petgo.content.event.ContentPublishedEvent;
import com.petgo.profile.domain.MilestoneCompletionSource;
import com.petgo.profile.event.CardSharedEvent;
import com.petgo.profile.event.HealthArchivedEvent;
import com.petgo.profile.event.ProfileCreatedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 里程碑系统自动完成订阅（Story 8.3，FR-42 / 决策 F16）。**护栏**：只用
 * {@code @TransactionalEventListener}（AFTER_COMMIT）+ {@code @Async} 订阅既有领域事件，
 * 幂等标记完成（不重复、不可撤销）——**禁 MQ/Quartz/缓存/新中间件**。所有完成经
 * {@link MilestoneCompletionService}（唯一约束保幂等）。
 *
 * <p>覆盖系统自动类节点：
 * <ul>
 *   <li>{@link ProfileCreatedEvent} → S1 档案创建完成。</li>
 *   <li>{@link ContentPublishedEvent} → S2 首张成长日历照片 + 计数 M10/L5；S5 首条日常分享。</li>
 *   <li>{@link CardSharedEvent} → S3 首次分享名片。</li>
 *   <li>{@link HealthArchivedEvent} → S4 首次保存问诊结论。</li>
 *   <li>{@link ContentCommentedEvent} → S14 首次被评论（排除自评）。</li>
 *   <li>{@link ContentLikedEvent} → S15 首次收到点赞（自赞 content 侧已不发事件）。</li>
 * </ul>
 * 组合依赖 C-L4/D-L4（健康全完成）与计数阈值由 {@link MilestoneCompletionService} 内部处理；
 * 陪伴满 30 天 M8 走 {@link MilestoneScheduledCompleter}（@Scheduled）。
 */
@Component
public class MilestoneAutoCompleteListener {

    private final MilestoneCompletionService completion;

    public MilestoneAutoCompleteListener(MilestoneCompletionService completion) {
        this.completion = completion;
    }

    @Async
    @TransactionalEventListener
    public void onProfileCreated(ProfileCreatedEvent e) {
        completion.completeForOwner(e.ownerId(), "S1", MilestoneCompletionSource.SYSTEM_AUTO);
    }

    @Async
    @TransactionalEventListener
    public void onContentPublished(ContentPublishedEvent e) {
        if (e.type() == ContentType.GROWTH_MOMENT) {
            // 计数类：首张 S2（≥1）/ 满 10 M10 / 满 30 L5（含 S2，统一走计数判定）。
            completion.onGrowthMomentCount(e.authorId(), e.authorGrowthMomentCount());
            // 「系统推送 + 当天发布」L 级节点回填：第一个生日 L1 / 满 100 天 L2 / 满 365 天 L3（8.6）。
            completion.completeDateGatedLNodesOnPublish(e.authorId());
        } else if (e.type() == ContentType.DAILY) {
            completion.completeForOwner(e.authorId(), "S5", MilestoneCompletionSource.SYSTEM_AUTO);
        }
        // KNOWLEDGE 不对应里程碑节点。
    }

    @Async
    @TransactionalEventListener
    public void onCardShared(CardSharedEvent e) {
        completion.completeForOwner(e.ownerId(), "S3", MilestoneCompletionSource.SYSTEM_AUTO);
    }

    @Async
    @TransactionalEventListener
    public void onHealthArchived(HealthArchivedEvent e) {
        completion.completeForOwner(e.ownerId(), "S4", MilestoneCompletionSource.SYSTEM_AUTO);
    }

    @Async
    @TransactionalEventListener
    public void onContentCommented(ContentCommentedEvent e) {
        if (e.commenterId() == e.contentAuthorId()) {
            return; // 自评不计「第一次被评论」。
        }
        completion.completeForOwner(e.contentAuthorId(), "S14", MilestoneCompletionSource.SYSTEM_AUTO);
    }

    @Async
    @TransactionalEventListener
    public void onContentLiked(ContentLikedEvent e) {
        // 自赞 content 侧已不发事件（无需再排除）。
        completion.completeForOwner(e.authorId(), "S15", MilestoneCompletionSource.SYSTEM_AUTO);
    }
}
