package com.tailtopia.notify.service;

import com.tailtopia.content.event.ContentCommentedEvent;
import com.tailtopia.content.event.ContentLikedEvent;
import com.tailtopia.content.event.ContentRemovedEvent;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.social.read.UserHideRelationReader;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 内容互动推送订阅（Story 6.3，FR-22B）。跨模块经领域事件（<b>不直访 content repository</b>）：
 * <ul>
 *   <li>{@link ContentLikedEvent} → 推送作者「有人赞了你的内容」。</li>
 *   <li>{@link ContentCommentedEvent} → 推送作者「有人评论了你的内容」（点击详情定位评论区）。</li>
 *   <li>{@link ContentRemovedEvent} → 推送作者「你发布的内容因违反社区规范已被移除」（Story 3.7 AC3，
 *       运营人工下架触发；<b>不说明举报人</b>、V1 <b>无申诉入口</b>；内容已 404 故 targetRef 仅作内部标识）。</li>
 * </ul>
 * 护栏：<b>自互动不推</b>（actor == author 跳过）；<b>逐条不合并</b>（每事件独立 send，无聚合/去抖，不引 MQ）。
 *
 * <p>V1 事件未富化标题/宠物名，故 body 用通用文案；标题富化需 Epic 3 事件扩展（避免 notify join content 表）。
 * 深链 token 由 NotificationService 生成；targetRef 存 postId 供后端解析。
 *
 * <h2>V1.1.4 Story 1.4：互动通知抑制（FR-94，安全攸关）</h2>
 *
 * <p>拉黑之后，对方的赞 / 评论 / 回复<b>一条通知都不该到</b>。抑制的实现是「<b>压根不调 send</b>」而不是
 * 「发了再隐藏」——{@code send} 一次调用同时做三件事（写 {@code notifications} 行 + Redis 角标自增 +
 * 离线推送），不调它，这三件事自然都不发生，<b>不需要任何额外代码去「撤销」</b>。
 *
 * <p><b>⚠️ 两处位置错一层就全错：</b>
 * <ol>
 *   <li><b>抑制只能写在本类的 {@code onContentLiked} / {@code onContentCommented} 里，
 *       绝不能写进 {@link NotificationService#send}</b> —— 那是全站唯一的通用发送方法，有 12 个调用点，
 *       覆盖封号 / 审核结果 / 内容被移除 / 退款驳回 / 工单结案等<b>系统通知</b>。写进去等于把它们一起掐了。
 *       同理，本类的 {@code onContentRemoved} 也<b>不接抑制</b>：它是「你的内容被运营移除」的平台告知。</li>
 *   <li><b>判据有两条，只做第一条会漏掉第三方</b>（见 {@link #suppressed}）。</li>
 * </ol>
 */
@Component
public class ContentNotifyListener {

    private final NotificationService notificationService;
    private final UserHideRelationReader hideRelations;

    public ContentNotifyListener(NotificationService notificationService,
            UserHideRelationReader hideRelations) {
        this.notificationService = notificationService;
        this.hideRelations = hideRelations;
    }

    /**
     * 这条互动通知要不要压掉 —— <b>两条判据，任一命中即不发</b>。
     *
     * <ol>
     *   <li><b>接收者隐藏了 actor</b>（{@code holder = 接收者}）—— 对应评论侧的 R1，「我不想被他打扰」。</li>
     *   <li><b>内容作者隐藏了 actor</b>（{@code holder = contentAuthorId}）—— 对应评论侧的 R2。
     *       <b>⚠️ 这条才是关键</b>：接收者可能<b>不是</b>内容作者（B 在 A 的帖子下回复了 C 的评论，接收者是 C）。
     *       A 拉黑 B 之后那条回复因 R2 对所有人隐藏，此时若只判第一条，
     *       <b>C 会收到一条点进去什么都没有的通知</b> —— 一对比就能推断出屏蔽机制存在。
     *       PRD §3.2 第 6 条列的三个场景里收通知的都是拉黑发起方本人，第三方这一支是 PRD 的空白。</li>
     * </ol>
     *
     * <p>两条都<b>不区分来源</b>（主动拉黑与举报隐藏都算）——与主页访问校验只认 BLOCK 正好相反，别写混。
     * 同步查库、走唯一索引，<b>不加缓存</b>（AD-18）；接收者恰好就是内容作者时退化成一次查询。
     */
    private boolean suppressed(long recipientId, long actorId, long contentAuthorId) {
        if (hideRelations.isHidden(recipientId, actorId)) {
            return true; // ① 接收者隐藏了 actor
        }
        // ② 内容作者隐藏了 actor（接收者 == 内容作者时与①同源，不必再查一次）
        return recipientId != contentAuthorId && hideRelations.isHidden(contentAuthorId, actorId);
    }

    @TransactionalEventListener
    public void onContentLiked(ContentLikedEvent event) {
        if (event.likerId() == event.authorId()) {
            return; // 自赞不推（双重保险；content 侧已不发自赞事件）
        }
        // 点赞场景下接收者就是内容作者，两条判据退化为同一条；仍走同一个方法，与评论分支保持同构。
        if (suppressed(event.authorId(), event.likerId(), event.authorId())) {
            return;
        }
        notificationService.send(event.authorId(), NotificationType.CONTENT_LIKED,
                "有人赞了你的内容", "点击查看",
                NotificationType.CONTENT_LIKED.name(), String.valueOf(event.postId()));
    }

    @TransactionalEventListener
    public void onContentCommented(ContentCommentedEvent event) {
        String ref = String.valueOf(event.postId());
        // 通知内容作者（自评不推）。
        if (event.commenterId() != event.contentAuthorId()
                && !suppressed(event.contentAuthorId(), event.commenterId(), event.contentAuthorId())) {
            notificationService.send(event.contentAuthorId(), NotificationType.CONTENT_COMMENTED,
                    "有人评论了你的内容", "点击查看", NotificationType.CONTENT_COMMENTED.name(), ref);
        }
        // Bug 20260625-088：回复二级评论时，另行通知被回复的一级评论作者（parentAuthorId）。
        // 去重：排除自回复（== commenter）、以及与内容作者重复（上面已推，避免双推）。
        //
        // ⚠️ 这里就是 R3 的落点：接收者是 parent（第三方 C），而第二条判据的 holder 是
        // contentAuthorId（A）——**两个不同的人**，所以最容易漏。漏了 C 就会收到一条
        // 点进去什么都没有的通知（那条回复已被 R2 对所有人隐藏）。
        Long parent = event.parentAuthorId();
        if (parent != null && parent != event.commenterId() && parent != event.contentAuthorId()
                && !suppressed(parent, event.commenterId(), event.contentAuthorId())) {
            notificationService.send(parent, NotificationType.CONTENT_COMMENTED,
                    "有人回复了你的评论", "点击查看", NotificationType.CONTENT_COMMENTED.name(), ref);
        }
    }

    /**
     * 内容被运营下架（Story 3.7 AC3）→ 通知作者内容因违规已被移除。无举报人信息、无申诉入口；
     * 内容已 404，深链仅承载 postId 作内部标识（点击不导向有效内容）。驳回（DISMISSED）不发事件故不触达此处。
     */
    @TransactionalEventListener
    public void onContentRemoved(ContentRemovedEvent event) {
        notificationService.send(event.authorId(), NotificationType.CONTENT_REMOVED,
                "内容已被移除", "你发布的内容因违反社区规范已被移除",
                NotificationType.CONTENT_REMOVED.name(), String.valueOf(event.postId()));
    }
}
