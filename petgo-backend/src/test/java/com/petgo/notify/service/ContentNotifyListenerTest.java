package com.petgo.notify.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.petgo.content.event.ContentCommentedEvent;
import com.petgo.content.event.ContentLikedEvent;
import com.petgo.notify.domain.NotificationType;
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

    private static long anyLongArg() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    private static NotificationType any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
