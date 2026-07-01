package com.tailtopia.notify.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tailtopia.content.event.ContentCommentedEvent;
import com.tailtopia.content.event.ContentLikedEvent;
import com.tailtopia.content.event.ContentRemovedEvent;
import com.tailtopia.notify.domain.NotificationType;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * L0：内容互动推送 —— 被赞/被评推给作者；自互动不推；逐条独立 send。
 */
@ExtendWith(MockitoExtension.class)
class ContentNotifyListenerTest {

    @Mock
    NotificationService notificationService;

    private ContentNotifyListener listener() {
        return new ContentNotifyListener(notificationService);
    }

    @Test
    void likedNotifiesAuthor() {
        listener().onContentLiked(new ContentLikedEvent(55L, 2L, 9L, Instant.now()));
        verify(notificationService).send(eq(9L), eq(NotificationType.CONTENT_LIKED),
                anyString(), anyString(), eq(NotificationType.CONTENT_LIKED.name()), eq("55"));
    }

    @Test
    void selfLikeNotPushed() {
        listener().onContentLiked(new ContentLikedEvent(55L, 9L, 9L, Instant.now()));
        verify(notificationService, never()).send(anyLongArg(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void commentNotifiesContentAuthor() {
        listener().onContentCommented(new ContentCommentedEvent(55L, 7L, 2L, 9L, null, Instant.now()));
        verify(notificationService).send(eq(9L), eq(NotificationType.CONTENT_COMMENTED),
                anyString(), anyString(), eq(NotificationType.CONTENT_COMMENTED.name()), eq("55"));
    }

    @Test
    void selfCommentNotPushed() {
        listener().onContentCommented(new ContentCommentedEvent(55L, 7L, 9L, 9L, null, Instant.now()));
        verify(notificationService, never()).send(anyLongArg(), any(), anyString(), anyString(), anyString(), anyString());
    }

    // Bug 20260625-088：回复二级评论时，被回复人(parentAuthor)也应收到通知。
    @Test
    void replyNotifiesBothContentAuthorAndParentAuthor() {
        // post 作者 9；评论者 2 回复了 5 的一级评论。
        listener().onContentCommented(new ContentCommentedEvent(55L, 7L, 2L, 9L, 5L, Instant.now()));
        verify(notificationService).send(eq(9L), eq(NotificationType.CONTENT_COMMENTED),
                anyString(), anyString(), eq(NotificationType.CONTENT_COMMENTED.name()), eq("55"));
        verify(notificationService).send(eq(5L), eq(NotificationType.CONTENT_COMMENTED),
                anyString(), anyString(), eq(NotificationType.CONTENT_COMMENTED.name()), eq("55"));
    }

    @Test
    void replyToOwnCommentDoesNotNotifySelf() {
        // 评论者 5 回复自己的一级评论；内容作者 9。仅通知作者 9，不给自己(5)发。
        listener().onContentCommented(new ContentCommentedEvent(55L, 7L, 5L, 9L, 5L, Instant.now()));
        verify(notificationService).send(eq(9L), eq(NotificationType.CONTENT_COMMENTED),
                anyString(), anyString(), anyString(), eq("55"));
        verify(notificationService, never()).send(eq(5L), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void replyWhereParentIsContentAuthorNotifiedOnce() {
        // 被回复人 9 恰是内容作者：只推一条（去重，不双推）。
        listener().onContentCommented(new ContentCommentedEvent(55L, 7L, 2L, 9L, 9L, Instant.now()));
        verify(notificationService, org.mockito.Mockito.times(1)).send(eq(9L), any(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void contentRemovedNotifiesAuthor() {
        listener().onContentRemoved(new ContentRemovedEvent(55L, 9L, Instant.now()));
        verify(notificationService).send(eq(9L), eq(NotificationType.CONTENT_REMOVED),
                anyString(), anyString(), eq(NotificationType.CONTENT_REMOVED.name()), eq("55"));
    }

    private static long anyLongArg() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    private static NotificationType any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
