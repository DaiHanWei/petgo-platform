package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.ContentLike;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.LikeResponse;
import com.tailtopia.content.event.ContentLikedEvent;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

/** L0：点赞开关 + 幂等 + 计数 + 互动事件（自赞不发）+ 帖不可见 404（AC1/AC2）。 */
class LikeServiceTest {

    private ContentLikeRepository likes;
    private ContentPostRepository posts;
    private ApplicationEventPublisher events;
    private LikeService service;

    @BeforeEach
    void setUp() {
        likes = Mockito.mock(ContentLikeRepository.class);
        posts = Mockito.mock(ContentPostRepository.class);
        events = Mockito.mock(ApplicationEventPublisher.class);
        service = new LikeService(likes, posts, events);
    }

    private void postBy(long postId, long authorId) {
        ContentPost p = ContentPost.publish(authorId, ContentType.DAILY, null, "x", null);
        try {
            var f = ContentPost.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, postId);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        when(posts.findById(postId)).thenReturn(Optional.of(p));
    }

    @Test
    void likeInsertsCountsAndPublishesEventForOthersPost() {
        postBy(1L, 7L); // 作者 7
        when(likes.existsByPostIdAndUserId(1L, 9L)).thenReturn(false);
        when(likes.countByPostId(1L)).thenReturn(1L);

        LikeResponse r = service.like(1L, 9L); // 点赞者 9 ≠ 作者
        assertThat(r.liked()).isTrue();
        assertThat(r.likeCount()).isEqualTo(1L);
        verify(likes).save(any(ContentLike.class));
        ArgumentCaptor<ContentLikedEvent> ev = ArgumentCaptor.forClass(ContentLikedEvent.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue().postId()).isEqualTo(1L);
        assertThat(ev.getValue().likerId()).isEqualTo(9L);
        assertThat(ev.getValue().authorId()).isEqualTo(7L);
    }

    @Test
    void selfLikeDoesNotPublishEvent() {
        postBy(1L, 7L);
        when(likes.existsByPostIdAndUserId(1L, 7L)).thenReturn(false);
        when(likes.countByPostId(1L)).thenReturn(1L);

        service.like(1L, 7L); // 自赞
        verify(likes).save(any(ContentLike.class));
        verify(events, never()).publishEvent(any());
    }

    @Test
    void repeatLikeIsIdempotentNoDoubleSaveNoEvent() {
        postBy(1L, 7L);
        when(likes.existsByPostIdAndUserId(1L, 9L)).thenReturn(true); // 已赞
        when(likes.countByPostId(1L)).thenReturn(1L);

        LikeResponse r = service.like(1L, 9L);
        assertThat(r.liked()).isTrue();
        assertThat(r.likeCount()).isEqualTo(1L);
        verify(likes, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void unlikeDeletesAndIsIdempotent() {
        postBy(1L, 7L);
        when(likes.countByPostId(1L)).thenReturn(0L);

        LikeResponse r = service.unlike(1L, 9L);
        assertThat(r.liked()).isFalse();
        assertThat(r.likeCount()).isZero();
        verify(likes, times(1)).deleteByPostIdAndUserId(1L, 9L);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void likeOnMissingPostIsNotFound() {
        when(posts.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.like(99L, 9L)).isInstanceOf(AppException.class);
        verify(likes, never()).save(any());
    }
}
