package com.tailtopia.collect.service;

import static com.tailtopia.collect.CollectTestPosts.post;
import static com.tailtopia.collect.CollectTestPosts.published;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.moderation.service.ReportService;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** L0：V1.4.0 ④ Story 2.1 · AC4 —— 收藏可见性口径（P-5 双向、批量零 N+1）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CollectVisibilityTest {

    @Mock ReportService reports;
    @Mock UserHideRelationReader hides;

    private static final long ME = 7L;

    private CollectVisibility vis() {
        when(reports.reportedPostIds(anyLong(), any())).thenReturn(Set.of());
        when(hides.hiddenEitherWay(anyLong(), any())).thenReturn(Set.of());
        return new CollectVisibility(reports, hides);
    }

    @Test
    void statusRules() {
        CollectVisibility v = vis();
        assertThat(v.isVisibleTo(ME, post(1L, 9L, PostStatus.PUBLISHED, ContentVisibility.PUBLIC, true))).isFalse();
        assertThat(v.isVisibleTo(ME, post(2L, 9L, PostStatus.AUTHOR_DEACTIVATED, ContentVisibility.PUBLIC, false)))
                .isFalse();
        assertThat(v.isVisibleTo(ME, post(3L, 9L, PostStatus.UNDER_REVIEW, ContentVisibility.PUBLIC, false))).isFalse();
        assertThat(v.isVisibleTo(ME, post(4L, ME, PostStatus.UNDER_REVIEW, ContentVisibility.PUBLIC, false))).isTrue();
        assertThat(v.isVisibleTo(ME, published(5L, 9L))).isTrue();
        // 不看 visibility：私密帖由收藏入口 422 挡住，取消时不区分
        assertThat(v.isVisibleTo(ME, post(6L, 9L, PostStatus.PUBLISHED, ContentVisibility.PRIVATE, false))).isTrue();
    }

    @Test
    void reportedIsInvisible() {
        CollectVisibility v = vis();
        when(reports.reportedPostIds(eq(ME), any())).thenReturn(Set.of(5L));
        assertThat(v.isVisibleTo(ME, published(5L, 9L))).isFalse();
    }

    @Test
    void hiddenEitherWayIsInvisible() {
        CollectVisibility v = vis();
        when(hides.hiddenEitherWay(eq(ME), any())).thenReturn(Set.of(9L));
        assertThat(v.isVisibleTo(ME, published(5L, 9L))).isFalse();
        assertThat(v.visiblePostIds(ME, List.of(published(5L, 9L), published(6L, 10L)))).containsExactly(6L);
    }

    @Test
    void emptyInputMakesZeroCalls() {
        CollectVisibility v = new CollectVisibility(reports, hides);
        assertThat(v.visiblePostIds(ME, List.of())).isEmpty();
        assertThat(v.isVisibleTo(ME, null)).isFalse();
        verifyNoInteractions(reports, hides);
    }

    @Test
    void ownPostsSkipHideLookup() {
        CollectVisibility v = vis();
        assertThat(v.visiblePostIds(ME, List.of(published(1L, ME), published(2L, ME)))).containsExactlyInAnyOrder(1L, 2L);
        verify(hides, never()).hiddenEitherWay(anyLong(), any());
    }

    @Test
    void batchOfTwentyUsesOneQueryEach() {
        CollectVisibility v = vis();
        List<ContentPost> twenty = LongStream.rangeClosed(1, 20).mapToObj(i -> published(i, 100 + i)).toList();
        assertThat(v.visiblePostIds(ME, twenty)).hasSize(20);
        verify(reports, times(1)).reportedPostIds(eq(ME), any());
        verify(hides, times(1)).hiddenEitherWay(eq(ME), any());
    }
}
