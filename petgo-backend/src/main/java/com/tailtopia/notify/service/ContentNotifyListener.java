package com.tailtopia.notify.service;

import com.tailtopia.content.event.ContentCommentedEvent;
import com.tailtopia.content.event.ContentLikedEvent;
import com.tailtopia.content.event.ContentRemovedEvent;
import com.tailtopia.notify.domain.NotificationType;
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
 */
@Component
public class ContentNotifyListener {

    private final NotificationService notificationService;

    public ContentNotifyListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @TransactionalEventListener
    public void onContentLiked(ContentLikedEvent event) {
        if (event.likerId() == event.authorId()) {
            return; // 自赞不推（双重保险；content 侧已不发自赞事件）
        }
        notificationService.send(event.authorId(), NotificationType.CONTENT_LIKED,
                "有人赞了你的内容", "点击查看",
                NotificationType.CONTENT_LIKED.name(), String.valueOf(event.postId()));
    }

    @TransactionalEventListener
    public void onContentCommented(ContentCommentedEvent event) {
        String ref = String.valueOf(event.postId());
        // 通知内容作者（自评不推）。
        if (event.commenterId() != event.contentAuthorId()) {
            notificationService.send(event.contentAuthorId(), NotificationType.CONTENT_COMMENTED,
                    "有人评论了你的内容", "点击查看", NotificationType.CONTENT_COMMENTED.name(), ref);
        }
        // Bug 20260625-088：回复二级评论时，另行通知被回复的一级评论作者（parentAuthorId）。
        // 去重：排除自回复（== commenter）、以及与内容作者重复（上面已推，避免双推）。
        Long parent = event.parentAuthorId();
        if (parent != null && parent != event.commenterId() && parent != event.contentAuthorId()) {
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
