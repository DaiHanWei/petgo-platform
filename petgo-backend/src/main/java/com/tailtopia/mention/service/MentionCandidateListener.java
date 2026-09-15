package com.tailtopia.mention.service;

import com.tailtopia.content.event.ContentCommentedEvent;
import com.tailtopia.content.event.ContentLikedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 把互动喂进 @ 候选集（V1.3.0 batch-b1 Story 3.1 · AC2 的「写入时机」那一半）。
 *
 * <h2>🔴 写入时机：**随互动异步写**（二选一里选的这个）</h2>
 * 消费的是**既有**的两个领域事件 —— {@link ContentLikedEvent} 与 {@link ContentCommentedEvent}，
 * <b>没有在 content 侧新增任何发布点</b>，也<b>不直读 content 的仓储</b>（模块边界）。
 *
 * <p>⚠️ 护栏：异步只用 {@code @Async}，<b>不引 MQ、不引调度中间件</b>（CLAUDE.md）。
 *
 * <h2>🔴 {@code @TransactionalEventListener} + {@code @Async}：两个注解都不能少</h2>
 * <ul>
 *   <li><b>AFTER_COMMIT</b>（{@code @TransactionalEventListener} 的默认阶段）：评论/点赞那个事务
 *       万一回滚了，候选集不该留下痕迹；</li>
 *   <li><b>{@code @Async}</b>：评论 / 点赞是热路径，而候选集晚半秒没人看得出来。</li>
 * </ul>
 * ⚠️ 两者合用时监听方法**跑在另一个线程、另一个事务里** ——
 * 所以维护服务那两个方法标的是 {@code REQUIRES_NEW}，不依赖调用方的事务。
 *
 * <h2>🛡 失败只记一行日志，绝不往外抛</h2>
 * 这张表是 comments / content_likes 的**派生物**：写丢一次的后果只是
 * 「那个人这次没进候选集」，重新互动一次就回来了。
 * 让它把异步线程的异常栈打满，或者（更糟）影响到别的监听器，完全不值当。
 */
@Component
public class MentionCandidateListener {

    private static final Logger log = LoggerFactory.getLogger(MentionCandidateListener.class);

    private final MentionCandidateMaintenanceService maintenance;

    public MentionCandidateListener(MentionCandidateMaintenanceService maintenance) {
        this.maintenance = maintenance;
    }

    /**
     * 「赞过我的」—— <b>单向</b>：点赞者进**作者**的候选集，反过来不写。
     *
     * <p>⚠️ PRD §2.3 的口径里只有「赞过我的」，<b>没有「我赞过的」</b>。
     * 顺手写成双向的话，一个只会点赞、从不说话的人的候选集里会塞满他赞过的所有作者 ——
     * 而他跟那些人其实并没有"打过交道"。
     *
     * <p>⚠️ 自赞根本不发这个事件（{@code LikeService} 里就跳过了），这里不必再判。
     */
    @Async
    @TransactionalEventListener
    public void onContentLiked(ContentLikedEvent event) {
        safely(() -> maintenance.recordOneWay(event.authorId(), event.likerId(), event.createdAt()));
    }

    /**
     * 「评论过我 / 我评论过 / 与我互相回复对话过」—— <b>双向</b>，且最多两对关系：
     * <ol>
     *   <li>评论者 ↔ 内容作者；</li>
     *   <li>评论者 ↔ 被回复的一级评论作者（一级评论时该字段为 null，这一对不存在）。</li>
     * </ol>
     * 第二对就是口径里的「与我互相回复对话过」—— 少了它，在别人帖子下聊了半天的两个人
     * 互相 @ 不到，而那恰恰是最需要 @ 的场景。
     */
    @Async
    @TransactionalEventListener
    public void onContentCommented(ContentCommentedEvent event) {
        // ⚠️ 两对关系**各包一层** —— 包在一起的话，第一对写失败就会把第二对整个跳过，
        // 而第二对正是「与我互相回复对话过」这条口径**唯一**的落点
        // （code-review 2026-09-15）。
        safely(() -> maintenance.recordMutual(
                event.commenterId(), event.contentAuthorId(), event.createdAt()));
        Long parentAuthorId = event.parentAuthorId();
        if (parentAuthorId != null) {
            safely(() -> maintenance.recordMutual(
                    event.commenterId(), parentAuthorId, event.createdAt()));
        }
    }

    /** 见类注释：派生数据，写丢一次不值得把异常抛进异步线程池。 */
    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // ⚠️ 只记异常本身，**不记任何用户 id 之外的东西**（SLF4J JSON，严禁 PII）。
            log.warn("写 @ 候选集失败（派生数据，已忽略）", e);
        }
    }
}
