package com.tailtopia.profile.service;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.event.ContentCommentedEvent;
import com.tailtopia.content.event.ContentLikedEvent;
import com.tailtopia.content.event.ContentPublishedEvent;
import com.tailtopia.consult.event.ConsultClosedEvent;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.event.CardSharedEvent;
import com.tailtopia.profile.event.HealthArchivedEvent;
import com.tailtopia.profile.event.HealthRecordCreatedEvent;
import com.tailtopia.profile.event.ProfileCreatedEvent;
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

    /**
     * 里程碑第四触发路径（Story 7.2，FR-45C）：结构化健康记录创建 → 自动完成对应里程碑。
     * VACCINE→M3（疫苗）/ DEWORM→M4（驱虫）；其它类型无对应节点，忽略。幂等（唯一约束），与打卡路径互不冲突。
     */
    @Async
    @TransactionalEventListener
    public void onHealthRecordCreated(HealthRecordCreatedEvent e) {
        String suffix = suffixFor(e.type());
        if (suffix != null) {
            completion.completeForOwner(e.ownerId(), suffix, MilestoneCompletionSource.SYSTEM_AUTO);
        }
        // Lulus Pemula 新手任务⑥（录入健康记录，任一 type）：可能是最后一块 → 尝试聚合解锁（7.3）。
        completion.maybeUnlockLulusPemulaForOwner(e.ownerId());
    }

    /**
     * 健康记录类型 → 里程碑后缀（FR-86 映射表，Story 5.1 补全 NEUTER）。
     *
     * <p>⚠️ 月经 / 自定义**刻意不映射任何里程碑**（PRD 明确：无对应节点），别顺手补上去。
     * M5「第一次看兽医」不在此表 —— 它由**兽医咨询结束**触发（见 {@link #onConsultClosed}），
     * 不是录健康记录触发。
     */
    private static String suffixFor(HealthRecordType type) {
        return switch (type) {
            case VACCINE -> "M3";
            case DEWORM -> "M4";
            case NEUTER -> "M9"; // Story 5.1 新增（2026-07-29 产品确认）
            case MENSTRUATION, CUSTOM -> null;
        };
    }

    /**
     * M5「第一次看兽医」：**真人兽医咨询结束**即自动完成（Story 5.1 · AC2）。
     *
     * <p>⚠️ <b>不需要、也不要加「排除 AI 问诊」的条件</b>：{@code ConsultClosedEvent} 只由
     * consult 模块的**真人兽医会话**发布，AI 分诊走 triage 模块、不发这个事件 —— 模块隔离已经
     * 天然满足「AI 问诊不解锁 M5」（OQ-17）。加一层 AI 判断只是冗余分支，反而让人以为可能漏。
     *
     * <p>与 S4「第一次保存兽医问诊结论」**分属两个独立订阅，不合并**：S4 订 {@code HealthArchivedEvent}
     * （用户可跳过存档），M5 订本事件（只要看过兽医就算）。合并会让「跳过存档」把 M5 一起吃掉。
     *
     * <p>幂等：{@code completeForOwner} 依赖 {@code milestone_completions} 的唯一约束，重复关闭安全。
     */
    @Async
    @TransactionalEventListener
    public void onConsultClosed(ConsultClosedEvent e) {
        completion.completeForOwner(e.userId(), "M5", MilestoneCompletionSource.SYSTEM_AUTO);
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
