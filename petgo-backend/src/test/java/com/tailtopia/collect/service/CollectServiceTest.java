package com.tailtopia.collect.service;

import static com.tailtopia.collect.CollectTestPosts.post;
import static com.tailtopia.collect.CollectTestPosts.published;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.collect.repository.ContentCollectionRepository;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.event.ContentCollectedEvent;
import com.tailtopia.content.event.ContentUncollectedEvent;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentDetailService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

/**
 * L0：V1.4.0 ④ Story 2.1 · AC2 / AC3 —— 帖子收藏 / 取消与事件门槛。
 *
 * <p>构造器里没有 {@code NotificationService}：本线不发任何通知（PRD「被收藏不通知作者」）。
 */
@ExtendWith(MockitoExtension.class)
class CollectServiceTest {

    @Mock ContentDetailService detail;
    @Mock ContentPostRepository posts;
    @Mock ContentCollectionRepository collections;
    @Mock CollectVisibility visibility;
    @Mock ApplicationEventPublisher events;

    private static final long A = 7L;
    private static final long B = 9L;

    private CollectService service() {
        return new CollectService(detail, posts, collections, visibility, events);
    }

    @Test
    void newCollectPublishesEvent() {
        ContentPost p = published(55L, B);
        when(detail.requireVisiblePost(55L, A)).thenReturn(p);
        when(collections.insertIfAbsent(A, 55L)).thenReturn(1);
        when(visibility.isVisibleTo(A, p)).thenReturn(true);

        assertThat(service().collect(55L, A)).isTrue();
        verify(events).publishEvent(new ContentCollectedEvent(55L, A, B));
    }

    @Test
    void repeatCollectDoesNotPublish() {
        ContentPost p = published(55L, B);
        when(detail.requireVisiblePost(55L, A)).thenReturn(p);
        when(collections.insertIfAbsent(A, 55L)).thenReturn(0);

        assertThat(service().collect(55L, A)).isTrue();
        verifyNoInteractions(events);
    }

    @Test
    void selfCollectAlsoPublishes() {
        ContentPost p = post(55L, B, PostStatus.UNDER_REVIEW, ContentVisibility.PUBLIC, false);
        when(detail.requireVisiblePost(55L, B)).thenReturn(p);
        when(collections.insertIfAbsent(B, 55L)).thenReturn(1);
        when(visibility.isVisibleTo(B, p)).thenReturn(true);

        service().collect(55L, B);
        verify(events).publishEvent(new ContentCollectedEvent(55L, B, B));
    }

    @Test
    void privatePostIs422AndNotWritten() {
        ContentPost p = post(55L, A, PostStatus.PUBLISHED, ContentVisibility.PRIVATE, false);
        when(detail.requireVisiblePost(55L, A)).thenReturn(p);

        AppException e = catchThrowableOfType(AppException.class, () -> service().collect(55L, A));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getType()).isEqualTo(ErrorTypes.COLLECT_NOT_PUBLIC);
        verify(collections, never()).insertIfAbsent(anyLong(), anyLong());
        verifyNoInteractions(events);
    }

    @Test
    void invisiblePost404PassesThroughBeforePrivacyCheck() {
        when(detail.requireVisiblePost(55L, A)).thenThrow(AppException.notFound("这条内容已不存在"));

        AppException e = catchThrowableOfType(AppException.class, () -> service().collect(55L, A));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(collections, events);
    }

    @Test
    void authorBlockedMeWritesRowButNoEvent() {
        ContentPost p = published(55L, B);
        when(detail.requireVisiblePost(55L, A)).thenReturn(p);
        when(collections.insertIfAbsent(A, 55L)).thenReturn(1);
        when(visibility.isVisibleTo(A, p)).thenReturn(false); // 作者拉黑我：详情单向可见、双向口径不可见

        assertThat(service().collect(55L, A)).isTrue();
        verify(collections).insertIfAbsent(A, 55L);
        verifyNoInteractions(events);
    }

    @Test
    void uncollectVisiblePublishes() {
        ContentPost p = published(55L, B);
        when(posts.findById(55L)).thenReturn(Optional.of(p));
        when(collections.deleteByUserIdAndPostId(A, 55L)).thenReturn(1);
        when(visibility.isVisibleTo(A, p)).thenReturn(true);

        assertThat(service().uncollect(55L, A)).isFalse();
        verify(events).publishEvent(new ContentUncollectedEvent(55L, A, B));
    }

    @Test
    void uncollectInvisibleDoesNotPublish() {
        ContentPost p = post(55L, B, PostStatus.PUBLISHED, ContentVisibility.PUBLIC, true);
        when(posts.findById(55L)).thenReturn(Optional.of(p));
        when(collections.deleteByUserIdAndPostId(A, 55L)).thenReturn(1);
        when(visibility.isVisibleTo(A, p)).thenReturn(false);

        assertThat(service().uncollect(55L, A)).isFalse();
        verifyNoInteractions(events);
    }

    @Test
    void uncollectMissingPostStillDeletesAndNeverThrows() {
        when(posts.findById(55L)).thenReturn(Optional.empty());
        when(collections.deleteByUserIdAndPostId(A, 55L)).thenReturn(1);

        assertThat(service().uncollect(55L, A)).isFalse();
        verify(collections).deleteByUserIdAndPostId(A, 55L);
        verifyNoInteractions(events, visibility);
    }

    @Test
    void uncollectNotCollectedDoesNotPublish() {
        ContentPost p = published(55L, B);
        when(posts.findById(55L)).thenReturn(Optional.of(p));
        when(collections.deleteByUserIdAndPostId(A, 55L)).thenReturn(0);

        assertThat(service().uncollect(55L, A)).isFalse();
        verify(visibility, never()).isVisibleTo(anyLong(), any());
        verifyNoInteractions(events);
    }
}
