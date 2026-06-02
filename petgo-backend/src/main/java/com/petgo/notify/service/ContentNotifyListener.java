package com.petgo.notify.service;

import com.petgo.content.event.ContentCommentedEvent;
import com.petgo.content.event.ContentLikedEvent;
import com.petgo.notify.domain.NotificationType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 内容互动推送订阅（Story 6.3，FR-22B）。跨模块经领域事件（<b>不直访 content repository</b>）：
 * <ul>
 *   <li>{@link ContentLikedEvent} → 推送作者「有人赞了你的内容」。</li>
 *   <li>{@link ContentCommentedEvent} → 推送作者「有人评论了你的内容」（点击详情定位评论区）。</li>
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
        if (event.commenterId() == event.contentAuthorId()) {
            return; // 自评不推
        }
        notificationService.send(event.contentAuthorId(), NotificationType.CONTENT_COMMENTED,
                "有人评论了你的内容", "点击查看",
                NotificationType.CONTENT_COMMENTED.name(), String.valueOf(event.postId()));
    }
}
