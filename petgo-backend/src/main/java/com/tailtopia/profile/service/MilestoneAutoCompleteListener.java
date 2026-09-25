package com.tailtopia.profile.service;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.event.ContentCommentedEvent;
import com.tailtopia.content.event.ContentLikedEvent;
import com.tailtopia.content.event.ContentPublishedEvent;
import com.tailtopia.consult.event.ConsultClosedEvent;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.MilestoneAutoEvent;
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
 * <p>覆盖系统自动类节点（一律以 {@link MilestoneAutoEvent} 表达「发生了什么」，
 * 由 {@link com.tailtopia.profile.domain.MilestoneAutoCompleteMap} 按物种查出完整 code）：
 * <ul>
 *   <li>{@link ProfileCreatedEvent} → 档案创建完成。</li>
 *   <li>{@link ContentPublishedEvent} → 首张成长日历照片 + 计数满 10 / 满 30；首条**对外可见**帖子。</li>
 *   <li>{@link CardSharedEvent} → 首次分享名片。</li>
 *   <li>{@link HealthArchivedEvent} → 首次保存问诊结论。</li>
 *   <li>{@link ContentCommentedEvent} → 首次被评论（排除自评）。</li>
 *   <li>{@link ContentLikedEvent} → 首次收到点赞（自赞 content 侧已不发事件）。</li>
 * </ul>
 * 组合依赖 C-L4/D-L4（健康全完成）与计数阈值由 {@link MilestoneCompletionService} 内部处理；
 * 陪伴满 30 天走 {@link MilestoneScheduledCompleter}（@Scheduled）。
 *
 * <p>⚠️ <b>本类不得再出现里程碑后缀字符串</b>（V1.3.0 Story 1.1 · AD-A4）：拼后缀寻址默认三张清单
 * 同号位含义相同，通用清单只有 16 项、猫狗各 31 项，曾一次造出五处线上错误。
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
        completion.completeForOwner(e.ownerId(), MilestoneAutoEvent.PROFILE_CREATED,
                MilestoneCompletionSource.SYSTEM_AUTO);
    }

    /**
     * 内容发布 → 首张成长日历照片 / 计数类 / 首条平台帖子。
     *
     * <p><b>S5「首条平台帖子」的判定口径（2026-08-05 修）</b>：按**是否对外可见**判，不按内容类型判。
     * 原实现只认 {@code DAILY}，但 V1.1.2 把 Diary（{@code GROWTH_MOMENT}）设成了有宠用户的**默认**
     * 发布类型、并用「同步到 Moment」开关决定它是否进 Feed（Story 4.1/4.2 只改 visibility、
     * **不改 type**）。结果是：顺着默认路径发帖、内容已出现在广场，S5 却永不解锁 —— 与节点文案
     * 「发布你在平台上的第一条帖子」直接矛盾，且 S5 是新手任务六件套之一，连带聚合奖励也卡死。
     *
     * <p>⚠️ {@code PRIVATE} 一律不算：私密内容只进作者自己的档案，不进任何公开位，
     * 「平台发帖」语义不成立。**别为了让节点更好解锁而放宽这一条。**
     */
    @Async
    @TransactionalEventListener
    public void onContentPublished(ContentPublishedEvent e) {
        if (e.type() == ContentType.GROWTH_MOMENT) {
            // 计数类：首张（≥1）/ 满 10 / 满 30，统一走计数判定（按物种查表，通用宠物满 10 是 G-M4）。
            completion.onGrowthMomentCount(e.authorId(), e.authorGrowthMomentCount());
            // 「系统推送 + 当天发布」L 级节点回填：第一个生日 L1 / 满 100 天 L2 / 满 365 天 L3（8.6）。
            completion.completeDateGatedLNodesOnPublish(e.authorId());
        }
        // 首条平台帖子：DAILY 恒算；GROWTH_MOMENT / KNOWLEDGE 需 PUBLIC（对外可见）才算。幂等，与上面互不影响。
        if (isPlatformPost(e)) {
            completion.completeForOwner(e.authorId(), MilestoneAutoEvent.PLATFORM_POST,
                    MilestoneCompletionSource.SYSTEM_AUTO);
        }
    }

    /** 是否构成「平台上的一条帖子」：Moment 恒是；Diary / Tips 仅在公开时是（私密不算）。 */
    private static boolean isPlatformPost(ContentPublishedEvent e) {
        return e.type() == ContentType.DAILY || e.visibility() == ContentVisibility.PUBLIC;
    }

    @Async
    @TransactionalEventListener
    public void onCardShared(CardSharedEvent e) {
        completion.completeForOwner(e.ownerId(), MilestoneAutoEvent.CARD_SHARED,
                MilestoneCompletionSource.SYSTEM_AUTO);
    }

    @Async
    @TransactionalEventListener
    public void onHealthArchived(HealthArchivedEvent e) {
        completion.completeForOwner(e.ownerId(), MilestoneAutoEvent.CONSULT_ARCHIVED,
                MilestoneCompletionSource.SYSTEM_AUTO);
    }

    /**
     * 里程碑第四触发路径（Story 7.2，FR-45C）：结构化健康记录创建 → 自动完成对应里程碑。
     * 幂等（唯一约束），与打卡路径互不冲突。
     *
     * <p>🔴 <b>V1.3.0 Story 1.1 修正</b>：这条路径原先拼后缀 {@code M3}/{@code M4}，对通用宠物
     * （OTHER）直接指错了节点 —— 录疫苗点亮 <b>G-M3「陪伴满 30 天」</b>、录驱虫点亮
     * <b>G-M4「记录满 10 条」</b>，两处都是用户看得见的错点亮。现改为按物种查表：通用宠物
     * 录疫苗 → <b>G-M2</b>「第一次健康检查 / 疫苗」（决策 A-1），驱虫 / 绝育在通用清单
     * <b>没有对应节点</b>，不点亮任何条目。
     */
    @Async
    @TransactionalEventListener
    public void onHealthRecordCreated(HealthRecordCreatedEvent e) {
        MilestoneAutoEvent event = eventFor(e.type());
        if (event != null) {
            completion.completeForOwner(e.ownerId(), event, MilestoneCompletionSource.SYSTEM_AUTO);
        }
        // Lulus Pemula 新手任务⑥（录入健康记录，任一 type）：可能是最后一块 → 尝试聚合解锁（7.3）。
        completion.maybeUnlockLulusPemulaForOwner(e.ownerId());
    }

    /**
     * 健康记录类型 → 自动事件（FR-86 映射表，Story 5.1 补全 NEUTER）。
     *
     * <p>⚠️ 月经 / 自定义**刻意不映射任何里程碑**（PRD 明确：无对应节点），别顺手补上去。
     * 「第一次看兽医」不在此表 —— 它由**兽医咨询结束**触发（见 {@link #onConsultClosed}），
     * 不是录健康记录触发。
     *
     * <p>本表只回答「发生了什么」；「哪个物种点亮哪一条」在
     * {@link com.tailtopia.profile.domain.MilestoneAutoCompleteMap}，**不要把 code 搬回这里**。
     */
    private static MilestoneAutoEvent eventFor(HealthRecordType type) {
        return switch (type) {
            case VACCINE -> MilestoneAutoEvent.HEALTH_RECORD_VACCINE;
            case DEWORM -> MilestoneAutoEvent.HEALTH_RECORD_DEWORM;
            case NEUTER -> MilestoneAutoEvent.HEALTH_RECORD_NEUTER; // Story 5.1 新增（2026-07-29 产品确认）
            case MENSTRUATION, CUSTOM -> null;
        };
    }

    /**
     * 「第一次看兽医」：**真人兽医咨询结束**即自动完成（Story 5.1 · AC2）。猫狗 = C/D-M5；
     * 通用清单的对应节点是 G-M1，但它当前仍是打卡类，接入属 Story 1.2 —— 本 story 只改寻址、
     * 不改触发源，故通用宠物此路径维持不点亮（映射表已把这一点显式写死，不是漏写）。
     *
     * <p>⚠️ <b>不需要、也不要加「排除 AI 问诊」的条件</b>：{@code ConsultClosedEvent} 只由
     * consult 模块的**真人兽医会话**发布，AI 分诊走 triage 模块、不发这个事件 —— 模块隔离已经
     * 天然满足「AI 问诊不解锁该节点」（OQ-17）。加一层 AI 判断只是冗余分支，反而让人以为可能漏。
     *
     * <p>与 S4「第一次保存兽医问诊结论」**分属两个独立订阅，不合并**：S4 订 {@code HealthArchivedEvent}
     * （用户可跳过存档），本节点订本事件（只要看过兽医就算）。合并会让「跳过存档」把它一起吃掉。
     *
     * <p>幂等：{@code completeForOwner} 依赖 {@code milestone_completions} 的唯一约束，重复关闭安全。
     */
    @Async
    @TransactionalEventListener
    public void onConsultClosed(ConsultClosedEvent e) {
        completion.completeForOwner(e.userId(), MilestoneAutoEvent.CONSULT_CLOSED,
                MilestoneCompletionSource.SYSTEM_AUTO);
    }

    @Async
    @TransactionalEventListener
    public void onContentCommented(ContentCommentedEvent e) {
        if (e.commenterId() == e.contentAuthorId()) {
            return; // 自评不计「第一次被评论」。
        }
        completion.completeForOwner(e.contentAuthorId(), MilestoneAutoEvent.FIRST_COMMENT,
                MilestoneCompletionSource.SYSTEM_AUTO);
    }

    @Async
    @TransactionalEventListener
    public void onContentLiked(ContentLikedEvent e) {
        // 自赞 content 侧已不发事件（无需再排除）。
        completion.completeForOwner(e.authorId(), MilestoneAutoEvent.FIRST_LIKE,
                MilestoneCompletionSource.SYSTEM_AUTO);
    }
}
