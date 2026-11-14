package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0 · V1.5.0 likes Story 1.2（FR-218 第 2 项）：P95 重算的赞数走排序口径（不含自动赞），与打分分子同口径。 */
class FeedRankInteractionStatsTest {

    private final ContentPostRepository posts = mock(ContentPostRepository.class);
    private final ContentLikeRepository likes = mock(ContentLikeRepository.class);
    private final CommentRepository comments = mock(CommentRepository.class);
    private final FeedRankInteractionStats stats = new FeedRankInteractionStats(posts, likes, comments);

    private static ContentLikeRepository.PostLikeCount row(long postId, long n) {
        return new ContentLikeRepository.PostLikeCount() {
            @Override
            public Long getPostId() {
                return postId;
            }

            @Override
            public long getLikeCount() {
                return n;
            }
        };
    }

    @Test
    void usesOrganicLikeCountsNotDisplayCounts() {
        Instant since = Instant.parse("2026-11-01T00:00:00Z");
        when(posts.findPublicIdsPublishedSince(since)).thenReturn(List.of(1L, 2L));
        when(likes.countOrganicByPostIdIn(anyList())).thenReturn(List.of(row(1L, 2L), row(2L, 2L)));
        when(comments.countVisibleForViewerIn(anyList(), any())).thenReturn(List.of());

        List<Double> values = stats.interactionValues(since, 2.0);

        assertThat(values).containsExactly(2.0, 2.0);
        verify(likes, never()).countByPostIdIn(anyList());
    }
}
