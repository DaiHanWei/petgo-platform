package com.tailtopia.content.service;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.event.CommentSubmittedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L0：评论异步审核路由（内容审核 story 3 · 异步化）。PASS → 转 VISIBLE + 通知楼主（approveComment）；
 * 高危 / 降级 / 审核异常 → fail-closed 入人工队列（queueForReview），绝不自动放行。
 */
class CommentModerationListenerTest {

    private ContentModerationService moderation;
    private CommentService commentService;
    private CommentModerationListener listener;

    @BeforeEach
    void setUp() {
        moderation = mock(ContentModerationService.class);
        commentService = mock(CommentService.class);
        listener = new CommentModerationListener(moderation, commentService);
    }

    private static CommentSubmittedEvent event() {
        return new CommentSubmittedEvent(500L, "some body", 1);
    }

    @Test
    void passApprovesAndNotifiesOwner() {
        when(moderation.moderateComment(anyString())).thenReturn(CommentVerdict.PASS);
        listener.onCommentSubmitted(event());
        verify(commentService).approveComment(500L); // VISIBLE + ContentCommentedEvent
        verify(commentService, never()).queueForReview(anyLong(), anyInt());
    }

    @Test
    void highRiskGoesToManualReview() {
        when(moderation.moderateComment(anyString())).thenReturn(CommentVerdict.HIGH_RISK);
        listener.onCommentSubmitted(event());
        verify(commentService).queueForReview(500L, 1);
        verify(commentService, never()).approveComment(anyLong());
    }

    @Test
    void degradedGoesToManualReview() {
        when(moderation.moderateComment(anyString())).thenReturn(CommentVerdict.DEGRADED);
        listener.onCommentSubmitted(event());
        verify(commentService).queueForReview(500L, 1);
        verify(commentService, never()).approveComment(anyLong());
    }

    @Test
    void moderationExceptionFailClosedToQueue() {
        when(moderation.moderateComment(anyString())).thenThrow(new RuntimeException("boom"));
        listener.onCommentSubmitted(event());
        verify(commentService).queueForReview(500L, 1); // fail-closed，绝不自动放行
        verify(commentService, never()).approveComment(anyLong());
    }
}
