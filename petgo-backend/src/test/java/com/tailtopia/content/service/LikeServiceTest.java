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
import com.tailtopia.content.event.ContentUnlikedEvent;
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
        verify(events, never()).publishEvent(any(Object.class));
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
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void unlikeDeletesAndIsIdempotent() {
        postBy(1L, 7L);
        when(likes.countByPostId(1L)).thenReturn(0L);

        LikeResponse r = service.unlike(1L, 9L);
        assertThat(r.liked()).isFalse();
        assertThat(r.likeCount()).isZero();
        verify(likes, times(1)).deleteByPostIdAndUserId(1L, 9L);
        verify(events, never()).publishEvent(any(Object.class));
    }

    /** V1.4.0 ① Story 1.1 · AD-7：真删到行且非自赞 → 发 ContentUnlikedEvent（扣回点赞成长值）。 */
    @Test
    void unlikeThatDeletedARowPublishesUnlikedEvent() {
        postBy(1L, 7L);
        when(likes.deleteByPostIdAndUserId(1L, 9L)).thenReturn(1);

        service.unlike(1L, 9L);

        verify(events, times(1)).publishEvent(new ContentUnlikedEvent(1L, 9L, 7L));
    }

    /** 自赞后取消：本就没有点赞事件，取消也不发。 */
    @Test
    void unlikeOwnPostDoesNotPublish() {
        postBy(1L, 7L);
        when(likes.deleteByPostIdAndUserId(1L, 7L)).thenReturn(1);

        service.unlike(1L, 7L);

        verify(events, never()).publishEvent(any(Object.class));
    }

    /** V1.5.0 likes Story 1.1 · AC3：真新建一行 → created=true，发 automated=true 事件。 */
    @Test
    void likeAutomatedInsertsAndPublishesAutomatedEvent() {
        when(likes.insertAutomatedIfAbsent(1L, 15L)).thenReturn(1);
        when(likes.countByPostId(1L)).thenReturn(3L);

        LikeService.AutomatedLikeResult r = service.likeAutomated(1L, 15L, 7L);

        assertThat(r.created()).isTrue();
        assertThat(r.likeCount()).isEqualTo(3L);
        ArgumentCaptor<ContentLikedEvent> ev = ArgumentCaptor.forClass(ContentLikedEvent.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue().postId()).isEqualTo(1L);
        assertThat(ev.getValue().likerId()).isEqualTo(15L);
        assertThat(ev.getValue().authorId()).isEqualTo(7L);
        assertThat(ev.getValue().automated()).isTrue();
        verify(likes, never()).save(any());
        verify(posts, never()).findById(anyLong());
    }

    /** AC3：该号已赞（插入 0 行）→ created=false，不发事件。 */
    @Test
    void likeAutomatedAlreadyLikedIsNoOpWithoutEvent() {
        when(likes.insertAutomatedIfAbsent(1L, 15L)).thenReturn(0);
        when(likes.countByPostId(1L)).thenReturn(2L);

        LikeService.AutomatedLikeResult r = service.likeAutomated(1L, 15L, 7L);

        assertThat(r.created()).isFalse();
        assertThat(r.likeCount()).isEqualTo(2L);
        verify(events, never()).publishEvent(any(Object.class));
    }

    /** AC3 / A-AC46：App 点赞发出的事件 automated=false。 */
    @Test
    void appLikeEventIsNotAutomated() {
        postBy(1L, 7L);
        when(likes.existsByPostIdAndUserId(1L, 9L)).thenReturn(false);
        when(likes.countByPostId(1L)).thenReturn(1L);

        service.like(1L, 9L);

        ArgumentCaptor<ContentLikedEvent> ev = ArgumentCaptor.forClass(ContentLikedEvent.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue().automated()).isFalse();
        ArgumentCaptor<ContentLike> saved = ArgumentCaptor.forClass(ContentLike.class);
        verify(likes).save(saved.capture());
        assertThat(saved.getValue().isAutomated()).isFalse();
    }

    @Test
    void likeOnMissingPostIsNotFound() {
        when(posts.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.like(99L, 9L)).isInstanceOf(AppException.class);
        verify(likes, never()).save(any());
    }
}
