package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.FeedPageResponse;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

/** L0：硬过滤参数、分类映射、游标/hasMore 切片、作者匿名化（AC1/AC2/AC3，逻辑面）。 */
class FeedServiceTest {

    private ContentPostRepository posts;
    private AccountQueryService accounts;
    private ContentLikeRepository likes;
    private FeedService service;

    @BeforeEach
    void setUp() {
        posts = mock(ContentPostRepository.class);
        accounts = mock(AccountQueryService.class);
        likes = mock(ContentLikeRepository.class); // countByPostIdIn 默认返空表 → likeCount 默认 0
        service = new FeedService(posts, accounts, likes);
        // 默认作者视图：返回非注销，nickname 由 id 推。
        when(accounts.findAuthorViews(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().distinct().collect(Collectors.toMap(
                    id -> (Long) id,
                    id -> new AuthorView((Long) id, "u" + id, "https://cdn/" + id + ".jpg", false)));
        });
    }

    private static ContentPost post(long id, ContentType type, long authorId, Instant createdAt,
            List<String> images) {
        ContentPost p = ContentPost.publish(authorId, type, null, "text" + id, images);
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

    @Test
    void planningExcludesGrowthMoment() {
        when(posts.findFeed(any(Boolean.class), any(), any(Boolean.class), any(Boolean.class), any(), any(), any()))
                .thenReturn(List.of());
        service.loadFeed("PLANNING", "ALL", null);

        ArgumentCaptor<Boolean> excludeGrowth = ArgumentCaptor.forClass(Boolean.class);
        org.mockito.Mockito.verify(posts).findFeed(excludeGrowth.capture(), isNull(),
                eq(false), eq(false), isNull(), isNull(), any(Pageable.class));
        assertThat(excludeGrowth.getValue()).isTrue();
    }

    @Test
    void hasPetAndGuestDoNotExcludeGrowth() {
        when(posts.findFeed(any(Boolean.class), any(), any(Boolean.class), any(Boolean.class), any(), any(), any()))
                .thenReturn(List.of());
        service.loadFeed("HAS_PET", "ALL", null);
        service.loadFeed(null, "ALL", null); // 游客

        org.mockito.Mockito.verify(posts, org.mockito.Mockito.times(2))
                .findFeed(eq(false), isNull(), eq(false), eq(false), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void growthCategoryRequiresPetAndTypeFilter() {
        when(posts.findFeed(any(Boolean.class), any(), any(Boolean.class), any(Boolean.class), any(), any(), any()))
                .thenReturn(List.of());
        service.loadFeed("HAS_PET", "GROWTH_MOMENT", null);

        org.mockito.Mockito.verify(posts).findFeed(eq(false), eq(ContentType.GROWTH_MOMENT),
                eq(true), eq(false), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void pageSizeTwentyOneTriggersHasMoreAndNextCursor() {
        Instant base = Instant.parse("2026-06-02T00:00:00Z");
        // 返回 21 条（PAGE_SIZE+1）→ hasMore=true，截到 20。
        List<ContentPost> rows = IntStream.range(0, 21)
                .mapToObj(i -> post(100 - i, ContentType.DAILY, 1L, base.minusSeconds(i), null))
                .toList();
        when(posts.findFeed(any(Boolean.class), any(), any(Boolean.class), any(Boolean.class), any(), any(), any()))
                .thenReturn(rows);

        FeedPageResponse page = service.loadFeed("HAS_PET", "ALL", null);
        assertThat(page.items()).hasSize(20);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotNull();
        // nextCursor 指向第 20 条（切片末尾）。
        FeedCursor decoded = FeedCursor.decode(page.nextCursor());
        assertThat(decoded.id()).isEqualTo(rows.get(19).getId());
    }

    @Test
    void lastPageHasNoMoreAndNoCursor() {
        List<ContentPost> rows = List.of(
                post(2L, ContentType.DAILY, 1L, Instant.now(), List.of("https://cdn/a.jpg")));
        when(posts.findFeed(any(Boolean.class), any(), any(Boolean.class), any(Boolean.class), any(), any(), any()))
                .thenReturn(rows);

        FeedPageResponse page = service.loadFeed("HAS_PET", "ALL", null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        // 卡片投影：首图取 imageUrls[0]，不含点赞/评论计数（DTO 无此字段）。
        assertThat(page.items().get(0).firstImageUrl()).isEqualTo("https://cdn/a.jpg");
    }

    @Test
    void deletedAuthorAnonymizedInProjection() {
        List<ContentPost> rows = List.of(post(5L, ContentType.DAILY, 9L, Instant.now(), null));
        when(posts.findFeed(any(Boolean.class), any(), any(Boolean.class), any(Boolean.class), any(), any(), any()))
                .thenReturn(rows);
        when(accounts.findAuthorViews(anyList()))
                .thenReturn(Map.of(9L, AuthorView.anonymized(9L)));

        FeedPageResponse page = service.loadFeed(null, "ALL", null);
        assertThat(page.items().get(0).authorDeleted()).isTrue();
        assertThat(page.items().get(0).authorNickname()).isNull();
        assertThat(page.items().get(0).authorAvatarUrl()).isNull();
        assertThat(page.items().get(0).authorId()).isEqualTo(9L);
    }

    @Test
    void cursorDecodedAndPassedToRepo() {
        when(posts.findFeed(any(Boolean.class), any(), any(Boolean.class), any(Boolean.class), any(), any(), any()))
                .thenReturn(List.of());
        Instant ts = Instant.parse("2026-06-01T12:00:00Z");
        String cursor = new FeedCursor(ts, 50L).encode();

        service.loadFeed("ENTHUSIAST", "DAILY", cursor);

        org.mockito.Mockito.verify(posts).findFeed(eq(false), eq(ContentType.DAILY), eq(false),
                eq(true), eq(ts), eq(50L), any(Pageable.class));
    }
}
