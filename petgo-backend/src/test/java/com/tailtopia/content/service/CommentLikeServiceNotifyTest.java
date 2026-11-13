package com.tailtopia.content.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.content.event.CommentLikedEvent;
import com.tailtopia.content.repository.CommentLikeRepository;
import com.tailtopia.content.repository.CommentRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** 修复线：评论被点赞才发通知事件 —— 只在真正新增、非自赞、评论已公开时发。 */
class CommentLikeServiceNotifyTest {

    private final CommentRepository comments = mock(CommentRepository.class);
    private final CommentLikeRepository likes = mock(CommentLikeRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final CommentLikeService service = new CommentLikeService(comments, likes, events);

    private void commentBy(long authorId, CommentModerationStatus status) {
        Comment c = mock(Comment.class);
        when(c.getId()).thenReturn(70L);
        when(c.getPostId()).thenReturn(55L);
        when(c.getAuthorId()).thenReturn(authorId);
        when(c.getBody()).thenReturn("halo");
        when(c.getModerationStatus()).thenReturn(status);
        when(comments.findById(70L)).thenReturn(Optional.of(c));
    }

    @Test
    void newLikeOfOthersVisibleCommentPublishesEvent() {
        commentBy(9L, CommentModerationStatus.VISIBLE);
        when(likes.insertIfAbsent(70L, 2L)).thenReturn(1);
        service.like(70L, 2L);
        verify(events).publishEvent(new CommentLikedEvent(55L, 70L, 2L, 9L, "halo"));
    }

    @Test
    void repeatedLikeDoesNotPublish() {
        commentBy(9L, CommentModerationStatus.VISIBLE);
        when(likes.insertIfAbsent(70L, 2L)).thenReturn(0);
        service.like(70L, 2L);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void selfLikeDoesNotPublish() {
        commentBy(9L, CommentModerationStatus.VISIBLE);
        when(likes.insertIfAbsent(70L, 9L)).thenReturn(1);
        service.like(70L, 9L);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void likeOfNonVisibleCommentDoesNotPublish() {
        commentBy(9L, CommentModerationStatus.UNDER_REVIEW);
        when(likes.insertIfAbsent(70L, 2L)).thenReturn(1);
        service.like(70L, 2L);
        verify(events, never()).publishEvent(any(Object.class));
    }
}
