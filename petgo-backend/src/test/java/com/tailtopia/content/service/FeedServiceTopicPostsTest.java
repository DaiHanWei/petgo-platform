package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.config.domain.FeedRankConfig;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.FeedItemResponse;
import com.tailtopia.content.dto.FeedPageResponse;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

/**
 * L0 · V1.4.2 social-topic Story 4.2 · AC2 / AC6.4（mock 仓储）：话题页 latest / popular 分支、
 * 第一页顶置让位、取顶置异常降级、popular 按 id 顺序重排、非法参数 422。
 */
class FeedServiceTopicPostsTest {

    private static final long TOPIC_ID = 9L;

    private ContentPostRepository posts;
    private ContentPinService pins;
    private PlatformConfigService platformConfig;
    private FeedService service;

    @BeforeEach
    void setUp() {
        posts = mock(ContentPostRepository.class);
        AccountQueryService accounts = mock(AccountQueryService.class);
        pins = mock(ContentPinService.class);
        when(pins.activePin(any(), any())).thenReturn(Optional.empty());
        platformConfig = mock(PlatformConfigService.class);
        FeedRankConfig cfg = mock(FeedRankConfig.class);
        when(cfg.getCommentWeight()).thenReturn(2.0);
        when(platformConfig.feedRank()).thenReturn(cfg);
        var hide = mock(com.tailtopia.social.read.UserHideRelationReader.class);
        service = new FeedService(posts, accounts, mock(ContentLikeRepository.class), mock(CommentRepository.class),
                pins, mock(ContentTagQueryService.class), hide,
                mock(com.tailtopia.content.rank.FeedRecommendationService.class),
                new com.tailtopia.mention.service.MentionViewService(mock(AccountQueryService.class), hide),
                mock(com.tailtopia.social.read.FollowReader.class),
                mock(com.tailtopia.social.read.PetFollowReader.class),
                mock(com.tailtopia.topic.service.TopicQueryService.class),
                platformConfig);
        when(accounts.findAuthorViews(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().distinct().collect(Collectors.toMap(id -> id,
                    id -> new AuthorView(id, "u" + id, null, false, List.of())));
        });
        when(posts.findTopicFeed(anyLong(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), any(),
                any(Pageable.class))).thenReturn(List.of());
        when(posts.findTopicPopularIds(anyLong(), anyBoolean(), anyLong(), anyBoolean(), anyLong(), anyDouble(),
                anyInt(), anyInt())).thenReturn(List.of());
    }

    private static ContentPost post(long id, Instant createdAt) {
        ContentPost p = ContentPost.publish(100 + id, ContentType.DAILY, null, "text" + id, List.of());
        set(p, "id", id);
        set(p, "createdAt", createdAt);
        return p;
    }

    private static void set(ContentPost p, String field, Object value) {
        try {
            var f = ContentPost.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(p, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void pinContent(long contentId) {
        ContentPin pin = mock(ContentPin.class);
        when(pin.getContentId()).thenReturn(contentId);
        when(pins.activePin(eq(ContentPin.topicSlot(TOPIC_ID)), any())).thenReturn(Optional.of(pin));
    }

    // ---------------------------------------------------------------- latest

    @Test
    void latestFirstPageExcludesTopicPin() {
        pinContent(77L);
        service.loadTopicPosts(TOPIC_ID, null, null, null, 5L);

        verify(pins).activePin(eq("TOPIC:9"), any());
        verify(posts).findTopicFeed(eq(TOPIC_ID), eq(true), eq(5L), eq(true), eq(77L), eq(false), isNull(), isNull(),
                any(Pageable.class));
    }

    @Test
    void latestWithCursorDoesNotExclude() {
        pinContent(77L);
        String cursor = new FeedCursor(Instant.parse("2026-11-01T00:00:00Z"), 50L).encode();
        service.loadTopicPosts(TOPIC_ID, "latest", cursor, null, null);

        verify(pins, never()).activePin(any(), any());
        verify(posts).findTopicFeed(eq(TOPIC_ID), eq(false), isNull(), eq(false), isNull(), eq(true),
                eq(Instant.parse("2026-11-01T00:00:00Z")), eq(50L), any(Pageable.class));
    }

    @Test
    void latestPagesWithCursorAndNoRankMode() {
        Instant base = Instant.parse("2026-11-01T00:00:00Z");
        List<ContentPost> rows = IntStream.range(0, FeedService.PAGE_SIZE + 1)
                .mapToObj(i -> post(1000 - i, base.minusSeconds(i))).toList();
        when(posts.findTopicFeed(anyLong(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), any(),
                any(Pageable.class))).thenReturn(rows);

        FeedPageResponse r = service.loadTopicPosts(TOPIC_ID, null, null, null, null);
        assertThat(r.items()).hasSize(FeedService.PAGE_SIZE);
        assertThat(r.hasMore()).isTrue();
        assertThat(r.nextCursor()).isNotNull();
        assertThat(r.rankMode()).isNull();
        FeedCursor c = FeedCursor.decode(r.nextCursor());
        assertThat(c.id()).isEqualTo(1000 - (FeedService.PAGE_SIZE - 1));
    }

    @Test
    void pinLookupFailureDoesNotExcludeOrThrow() {
        when(pins.activePin(any(), any())).thenThrow(new IllegalStateException("boom"));

        service.loadTopicPosts(TOPIC_ID, "latest", null, null, null);
        verify(posts).findTopicFeed(eq(TOPIC_ID), eq(false), isNull(), eq(false), isNull(), eq(false), isNull(),
                isNull(), any(Pageable.class));

        service.loadTopicPosts(TOPIC_ID, "popular", null, 1, null);
        verify(posts).findTopicPopularIds(eq(TOPIC_ID), eq(false), eq(-1L), eq(false), eq(-1L), eq(2.0),
                eq(FeedService.PAGE_SIZE + 1), eq(0));
    }

    // ---------------------------------------------------------------- popular

    /**
     * 复审 CONFIRMED-1：popular 是页码分页，<b>每一页都排除同一条顶置</b>——只首页排除会让 OFFSET 错位
     * （原第 21 名在第 1、2 页各出现一次，顶置帖再也不出现）。
     */
    @Test
    void popularExcludesPinOnEveryPage() {
        pinContent(77L);
        service.loadTopicPosts(TOPIC_ID, "popular", null, 1, 5L);
        verify(posts).findTopicPopularIds(eq(TOPIC_ID), eq(true), eq(5L), eq(true), eq(77L), eq(2.0),
                eq(FeedService.PAGE_SIZE + 1), eq(0));

        service.loadTopicPosts(TOPIC_ID, "popular", null, 2, 5L);
        verify(posts).findTopicPopularIds(eq(TOPIC_ID), eq(true), eq(5L), eq(true), eq(77L), eq(2.0),
                eq(FeedService.PAGE_SIZE + 1), eq(FeedService.PAGE_SIZE));
    }

    @Test
    void popularDefaultsToPageOneAndReadsWeightEachRequest() {
        service.loadTopicPosts(TOPIC_ID, "POPULAR", null, null, null);
        service.loadTopicPosts(TOPIC_ID, "popular", null, 3, null);
        verify(platformConfig, org.mockito.Mockito.times(2)).feedRank();
        verify(posts).findTopicPopularIds(anyLong(), anyBoolean(), anyLong(), anyBoolean(), anyLong(), anyDouble(),
                anyInt(), eq(0));
        verify(posts).findTopicPopularIds(anyLong(), anyBoolean(), anyLong(), anyBoolean(), anyLong(), anyDouble(),
                anyInt(), eq(2 * FeedService.PAGE_SIZE));
    }

    @Test
    void popularReordersByRankedIds() {
        Instant t = Instant.parse("2026-11-01T00:00:00Z");
        when(posts.findTopicPopularIds(anyLong(), anyBoolean(), anyLong(), anyBoolean(), anyLong(), anyDouble(),
                anyInt(), anyInt())).thenReturn(List.of(3L, 1L, 2L));
        // findAllById 不保序
        when(posts.findAllById(List.of(3L, 1L, 2L))).thenReturn(List.of(post(1, t), post(2, t), post(3, t)));

        FeedPageResponse r = service.loadTopicPosts(TOPIC_ID, "popular", null, 1, null);
        assertThat(r.items()).extracting(FeedItemResponse::id).containsExactly(3L, 1L, 2L);
        assertThat(r.nextCursor()).isNull();
        assertThat(r.hasMore()).isFalse();
        assertThat(r.rankMode()).isNull();
    }

    @Test
    void popularHasMoreWhenExtraRow() {
        Instant t = Instant.parse("2026-11-01T00:00:00Z");
        List<Long> ids = IntStream.rangeClosed(1, FeedService.PAGE_SIZE + 1).mapToObj(i -> (long) i).toList();
        when(posts.findTopicPopularIds(anyLong(), anyBoolean(), anyLong(), anyBoolean(), anyLong(), anyDouble(),
                anyInt(), anyInt())).thenReturn(new java.util.ArrayList<Number>(ids));
        when(posts.findAllById(ids.subList(0, FeedService.PAGE_SIZE)))
                .thenReturn(ids.subList(0, FeedService.PAGE_SIZE).stream().map(i -> post(i, t)).toList());

        FeedPageResponse r = service.loadTopicPosts(TOPIC_ID, "popular", null, 1, null);
        assertThat(r.items()).hasSize(FeedService.PAGE_SIZE);
        assertThat(r.hasMore()).isTrue();
        assertThat(r.nextCursor()).isNull();
    }

    // ---------------------------------------------------------------- 422

    @Test
    void invalidSortOrPageIs422() {
        for (Runnable bad : List.<Runnable>of(
                () -> service.loadTopicPosts(TOPIC_ID, "foo", null, null, null),
                () -> service.loadTopicPosts(TOPIC_ID, "popular", null, 51, null),
                () -> service.loadTopicPosts(TOPIC_ID, "popular", null, 0, null))) {
            assertThatThrownBy(bad::run).isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getStatus())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }
        verifyNoInteractions(posts);
    }

    @Test
    void popularPage50IsAllowed() {
        service.loadTopicPosts(TOPIC_ID, "popular", null, FeedService.TOPIC_POPULAR_MAX_PAGE, null);
        verify(posts).findTopicPopularIds(anyLong(), anyBoolean(), anyLong(), anyBoolean(), anyLong(), anyDouble(),
                anyInt(), eq(49 * FeedService.PAGE_SIZE));
    }
}
