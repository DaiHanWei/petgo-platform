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
    private com.tailtopia.content.rank.FeedRecommendationService recommendations;
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
        // V1.1.6 Story 16.3：ALL Tab 走推荐序。本类绝大多数用例验的是**时间倒序**那条路径，
        // 所以它们改用分类 Tab（DAILY）—— 过滤与分页逻辑完全同一段代码。
        // ALL Tab 的分流本身另有几条专门的用例（见文件末尾）。
        recommendations = mock(com.tailtopia.content.rank.FeedRecommendationService.class);
        service = new FeedService(posts, accounts, likes, comments, pins,
                // V1.1.6 Story 5.2：装饰标签；本类不验它，给 mock（默认无标签）。
                mock(com.tailtopia.content.service.ContentTagQueryService.class),
                // V1.1.6 Story 4.4：顶置位隐藏过滤；本类不验它，mock 默认 isHidden=false（等于没拉黑）。
                mock(com.tailtopia.social.read.UserHideRelationReader.class),
                recommendations);
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
     * Story 4.1 · AC5：V1.0.0「状态 B 用户 Feed 不显示成长日历」**整条废止**。
     *
     * <p>⚠️ <b>V1.1.6 Story 16.3 起本用例的前提又变了一次</b>：{@code petStatus} 形参已<b>删除</b>
     * （AC3 —— 留着只会让后人误以为它还有作用），所以「不同宠物状态取数参数相同」这件事
     * 现在<b>在签名层面就不可能不成立</b>，没法再从形参上验。
     * 保留下来的是仍有意义的那半：<b>取数参数只随 viewerId 变化</b>，不随其他身份属性变化。
     */
    @Test
    void chronoQueryVariesOnlyByViewerId() {
        when(posts.findFeed(any(), any(Boolean.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Boolean.class), any(), any()))
                .thenReturn(List.of());

        service.loadFeed("DAILY", null, null, null);   // 游客
        service.loadFeed("DAILY", null, 42L, null);    // 登录

        org.mockito.Mockito.verify(posts).findFeed(eq(ContentType.DAILY), eq(false),
                eq(false), isNull(), eq(false), isNull(), isNull(), eq(false), isNull(),
                any(Pageable.class));
        org.mockito.Mockito.verify(posts).findFeed(eq(ContentType.DAILY), eq(false),
                eq(true), eq(42L), eq(false), isNull(), isNull(), eq(false), isNull(),
                any(Pageable.class));
    }

    @Test
    void growthCategoryRequiresPetAndTypeFilter() {
        when(posts.findFeed(any(), any(Boolean.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Boolean.class), any(), any()))
                .thenReturn(List.of());
        service.loadFeed("GROWTH_MOMENT", null, null, null);

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

        FeedPageResponse page = service.loadFeed("DAILY", null, null, null);
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

        FeedPageResponse page = service.loadFeed("DAILY", null, null, null);
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

        FeedPageResponse page = service.loadFeed("DAILY", null, null, null);
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

        service.loadFeed("DAILY", cursor, null, null);

        org.mockito.Mockito.verify(posts).findFeed(eq(ContentType.DAILY), eq(false),
                eq(false), isNull(), eq(true), eq(ts), eq(50L), eq(false), isNull(), any(Pageable.class));
    }

    @Test
    void loggedInViewerThreadsViewerIdForReporterFilter() {
        // 内容审核 cm-6 §5.4：登录用户 → hasViewer=true + viewerId 透传（后端排除本人已举报的帖）。
        when(posts.findFeed(any(), any(Boolean.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Boolean.class), any(), any()))
                .thenReturn(List.of());

        service.loadFeed("DAILY", null, 42L, null);

        org.mockito.Mockito.verify(posts).findFeed(eq(ContentType.DAILY), eq(false),
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

        var page = service.loadFeed("DAILY", null, null, null);

        assertThat(page.items()).isEmpty();
        // 降级 = 当作没有顶置继续走：不排除任何内容
        org.mockito.Mockito.verify(posts).findFeed(eq(ContentType.DAILY), eq(false), eq(false),
                isNull(), eq(false), isNull(), isNull(), eq(false), isNull(), any(Pageable.class));
    }

    /** 🛡 **只首屏让位** —— 带游标（后续页）时不查顶置、也不排除。 */
    @Test
    void laterPagesDoNotYieldAtAll() {
        when(posts.findFeed(any(), any(Boolean.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Boolean.class), any(), any()))
                .thenReturn(List.of());

        service.loadFeed("DAILY", new FeedCursor(java.time.Instant.now(), 9L).encode(), null, null);

        org.mockito.Mockito.verifyNoInteractions(pins);
        org.mockito.Mockito.verify(posts).findFeed(any(), any(Boolean.class), any(Boolean.class),
                any(), eq(true), any(), any(), eq(false), isNull(), any(Pageable.class));
    }

    // ── V1.1.6 Story 16.3：ALL Tab 与非 ALL Tab 是两条独立路径 ──────────

    private static com.tailtopia.content.rank.FeedRecommendationService.RankedPage rankedPage(
            List<ContentPost> rows, String next) {
        return new com.tailtopia.content.rank.FeedRecommendationService.RankedPage(
                rows, next, next != null);
    }

    /** 🔴 ALL Tab 走推荐序 —— 且<b>完全不碰</b>时间倒序那条查询。 */
    @Test
    void allTabUsesRecommendationPathAndNotTheChronoQuery() {
        List<ContentPost> rows = List.of(post(7L, ContentType.DAILY, 1L, Instant.now(), null));
        when(recommendations.page(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(rankedPage(rows, "next-token"));

        FeedPageResponse page = service.loadFeed("ALL", null, 5L, "sess-1");

        assertThat(page.items()).hasSize(1);
        assertThat(page.nextCursor()).isEqualTo("next-token");
        org.mockito.Mockito.verifyNoInteractions(posts);
    }

    /** 🛡 非 ALL Tab <b>完全不碰</b>推荐序 —— 分类 Tab 的代码一行没动。 */
    @Test
    void categoryTabNeverTouchesTheRecommendationPath() {
        when(posts.findFeed(any(), any(Boolean.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Boolean.class), any(), any()))
                .thenReturn(List.of());

        service.loadFeed("DAILY", null, 5L, "sess-1");

        org.mockito.Mockito.verifyNoInteractions(recommendations);
    }

    /**
     * 🔴 <b>降级链级别 4</b>：打分链路抛异常 → 整体回落纯时间倒序，用户无感。
     *
     * <p>🛡 而且回落走的是<b>同一套候选池过滤</b>（{@code hasViewer=true} + viewerId 透传）——
     * AC4 明写「任何级别下候选池的全部过滤都不得被绕过」。回落时把过滤丢掉就是拉黑白拉。
     */
    @Test
    void level4FallsBackToChronoAndKeepsAllFilters() {
        when(recommendations.page(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenThrow(new IllegalStateException("scoring blew up"));
        when(posts.findFeed(any(), any(Boolean.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Boolean.class), any(), any()))
                .thenReturn(List.of(post(3L, ContentType.DAILY, 1L, Instant.now(), null)));

        FeedPageResponse page = service.loadFeed("ALL", null, 42L, null);

        assertThat(page.items()).hasSize(1); // 用户拿到内容，不是 500、不是空页
        org.mockito.Mockito.verify(posts).findFeed(isNull(), eq(false),
                eq(true), eq(42L), // 🛡 举报者/拉黑过滤所依赖的两个参数仍然传下去了
                eq(false), isNull(), isNull(), eq(false), isNull(), any(Pageable.class));
    }

    /**
     * 🔴 <b>入参问题不能被降级吞掉</b>：游标非法必须照常 422。
     *
     * <p>不区分的话，客户端传了个坏游标会得到「一页时间倒序的内容」而不是报错 ——
     * 表现是「下拉刷新后又从头开始了」，且服务端一条错都不记，无从排查。
     */
    @Test
    void invalidCursorStillFailsInsteadOfSilentlyFallingBack() {
        when(recommendations.page(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenThrow(com.tailtopia.shared.error.AppException.validation("游标无效"));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.loadFeed("ALL", "garbage", null, null))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class);
        org.mockito.Mockito.verifyNoInteractions(posts);
    }

    /** 首屏让位的那条内容 id 传给推荐序；后续页不让位（沿用 Story 4.2 口径）。 */
    @Test
    void pinYieldIdIsPassedToRecommendationOnFirstPageOnly() {
        com.tailtopia.content.domain.ContentPin pin =
                mock(com.tailtopia.content.domain.ContentPin.class);
        when(pin.getContentId()).thenReturn(77L);
        when(pins.activePin(any(), any())).thenReturn(java.util.Optional.of(pin));
        when(recommendations.page(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(rankedPage(List.of(), null));

        service.loadFeed("ALL", null, null, null);
        org.mockito.Mockito.verify(recommendations)
                .page(isNull(), isNull(), isNull(), eq(FeedService.PAGE_SIZE), eq(77L));

        service.loadFeed("ALL", "some-cursor", null, null);
        org.mockito.Mockito.verify(recommendations)
                .page(isNull(), isNull(), eq("some-cursor"), eq(FeedService.PAGE_SIZE), isNull());
    }
}
