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
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

/** L0：硬过滤参数、分类映射、游标/hasMore 切片、作者匿名化（AC1/AC2/AC3，逻辑面）。 */
class FeedServiceTest {

    private ContentPostRepository posts;
    private AccountQueryService accounts;
    private ContentLikeRepository likes;
    private com.tailtopia.content.repository.CommentRepository comments;
    private ContentPinService pins;
    private FeedService service;

    @BeforeEach
    void setUp() {
        posts = mock(ContentPostRepository.class);
        accounts = mock(AccountQueryService.class);
        likes = mock(ContentLikeRepository.class); // countByPostIdIn 默认返空表 → likeCount 默认 0
        // V1.1.6 Story 3.1：默认返空表 → commentCount 默认 0、已赞默认 false
        comments = mock(com.tailtopia.content.repository.CommentRepository.class);
        // V1.1.6 Story 4.2：只首屏让位要查顶置；默认无顶置（Optional.empty）。
        pins = mock(ContentPinService.class);
        when(pins.activePin(any(), any())).thenReturn(java.util.Optional.empty());
        service = new FeedService(posts, accounts, likes, comments, pins,
                // V1.1.6 Story 5.2：装饰标签；本类不验它，给 mock（默认无标签）。
                mock(com.tailtopia.content.service.ContentTagQueryService.class));
        // 默认作者视图：返回非注销，nickname 由 id 推。
        when(accounts.findAuthorViews(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().distinct().collect(Collectors.toMap(
                    id -> (Long) id,
                    id -> new AuthorView((Long) id, "u" + id, "https://cdn/" + id + ".jpg", false, java.util.List.of())));
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

    /**
     * Story 4.1 · AC5：V1.0.0「状态 B 用户 Feed 不显示成长日历」**整条废止** ——
     * 公开内容对所有用户一视同仁，取数参数不再随宠物状态变化。
     *
     * <p>原来的两条断言（PLANNING → excludeGrowth=true / 其余 → false）随该规则一并作废：
     * `excludeGrowth` 形参已从 `findFeed` 移除，取而代之的是查询内部固化的
     * `visibility = PUBLIC` 过滤（AD-4 Rule 2）。
     */
    @Test
    void feedNoLongerBranchesOnPetStatus() {
        when(posts.findFeed(any(), any(Boolean.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Boolean.class), any(), any()))
                .thenReturn(List.of());

        service.loadFeed("PLANNING", "ALL", null, null);
        service.loadFeed("HAS_PET", "ALL", null, null);
        service.loadFeed(null, "ALL", null, null); // 游客

        // 三种身份 → 完全相同的取数参数（10 参：type, requirePet, hasViewer, viewerId,
        // hasCursor, cursorTs, cursorId, hasExclude, excludeId, pageable）。
        org.mockito.Mockito.verify(posts, org.mockito.Mockito.times(3))
                .findFeed(isNull(), eq(false), eq(false), isNull(), eq(false), isNull(), isNull(), eq(false), isNull(),
                        any(Pageable.class));
    }

    @Test
    void growthCategoryRequiresPetAndTypeFilter() {
        when(posts.findFeed(any(), any(Boolean.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Boolean.class), any(), any()))
                .thenReturn(List.of());
        service.loadFeed("HAS_PET", "GROWTH_MOMENT", null, null);

        org.mockito.Mockito.verify(posts).findFeed(eq(ContentType.GROWTH_MOMENT),
                eq(true), eq(false), isNull(), eq(false), isNull(), isNull(), eq(false), isNull(), any(Pageable.class));
    }

    @Test
    void pageSizeTwentyOneTriggersHasMoreAndNextCursor() {
        Instant base = Instant.parse("2026-06-02T00:00:00Z");
        // 返回 21 条（PAGE_SIZE+1）→ hasMore=true，截到 20。
        List<ContentPost> rows = IntStream.range(0, 21)
                .mapToObj(i -> post(100 - i, ContentType.DAILY, 1L, base.minusSeconds(i), null))
                .toList();
        when(posts.findFeed(any(), any(Boolean.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Boolean.class), any(), any()))
                .thenReturn(rows);

        FeedPageResponse page = service.loadFeed("HAS_PET", "ALL", null, null);
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
        when(posts.findFeed(any(), any(Boolean.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Boolean.class), any(), any()))
                .thenReturn(rows);

        FeedPageResponse page = service.loadFeed("HAS_PET", "ALL", null, null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        // 卡片投影：首图取 imageUrls[0]，不含点赞/评论计数（DTO 无此字段）。
        assertThat(page.items().get(0).firstImageUrl()).isEqualTo("https://cdn/a.jpg");
    }

    @Test
    void deletedAuthorAnonymizedInProjection() {
        List<ContentPost> rows = List.of(post(5L, ContentType.DAILY, 9L, Instant.now(), null));
        when(posts.findFeed(any(), any(Boolean.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Boolean.class), any(), any()))
                .thenReturn(rows);
        when(accounts.findAuthorViews(anyList()))
                .thenReturn(Map.of(9L, AuthorView.anonymized(9L)));

        FeedPageResponse page = service.loadFeed(null, "ALL", null, null);
        assertThat(page.items().get(0).authorDeleted()).isTrue();
        assertThat(page.items().get(0).authorNickname()).isNull();
        assertThat(page.items().get(0).authorAvatarUrl()).isNull();
        assertThat(page.items().get(0).authorId()).isEqualTo(9L);
    }

    @Test
    void cursorDecodedAndPassedToRepo() {
        when(posts.findFeed(any(), any(Boolean.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Boolean.class), any(), any()))
                .thenReturn(List.of());
        Instant ts = Instant.parse("2026-06-01T12:00:00Z");
        String cursor = new FeedCursor(ts, 50L).encode();

        service.loadFeed("ENTHUSIAST", "DAILY", cursor, null);

        org.mockito.Mockito.verify(posts).findFeed(eq(ContentType.DAILY), eq(false),
                eq(false), isNull(), eq(true), eq(ts), eq(50L), eq(false), isNull(), any(Pageable.class));
    }

    @Test
    void loggedInViewerThreadsViewerIdForReporterFilter() {
        // 内容审核 cm-6 §5.4：登录用户 → hasViewer=true + viewerId 透传（后端排除本人已举报的帖）。
        when(posts.findFeed(any(), any(Boolean.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Boolean.class), any(), any()))
                .thenReturn(List.of());

        service.loadFeed("HAS_PET", "ALL", null, 42L);

        org.mockito.Mockito.verify(posts).findFeed(isNull(), eq(false),
                eq(true), eq(42L), eq(false), isNull(), isNull(), eq(false), isNull(), any(Pageable.class));
    }

    /**
     * 🛡 **顶置取数失败不得连带整个首页失败**（AC3）。
     *
     * <p>这一处最容易漏：大家通常只想到"坑位端点挂了客户端不显示"，
     * 忘了首页取数**内部**也查了一次顶置（为了让位）。那一次抛异常若不接住，
     * 整个首页会 500 —— 一个运营位把主功能带崩。
     *
     * <p>降级的代价是那一次可能出现重复展示，明确接受。
     */
    @Test
    void feedSurvivesWhenThePinLookupBlowsUp() {
        when(pins.activePin(any(), any())).thenThrow(new IllegalStateException("boom"));
        when(posts.findFeed(any(), any(Boolean.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Boolean.class), any(), any()))
                .thenReturn(List.of());

        var page = service.loadFeed(null, "ALL", null, null);

        assertThat(page.items()).isEmpty();
        // 降级 = 当作没有顶置继续走：不排除任何内容
        org.mockito.Mockito.verify(posts).findFeed(isNull(), eq(false), eq(false), isNull(),
                eq(false), isNull(), isNull(), eq(false), isNull(), any(Pageable.class));
    }

    /** 🛡 **只首屏让位** —— 带游标（后续页）时不查顶置、也不排除。 */
    @Test
    void laterPagesDoNotYieldAtAll() {
        when(posts.findFeed(any(), any(Boolean.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Boolean.class), any(), any()))
                .thenReturn(List.of());

        service.loadFeed(null, "ALL", new FeedCursor(java.time.Instant.now(), 9L).encode(), null);

        org.mockito.Mockito.verifyNoInteractions(pins);
        org.mockito.Mockito.verify(posts).findFeed(any(), any(Boolean.class), any(Boolean.class),
                any(), eq(true), any(), any(), eq(false), isNull(), any(Pageable.class));
    }
}
