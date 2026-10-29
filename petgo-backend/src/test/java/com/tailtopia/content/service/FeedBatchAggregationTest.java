package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.FeedItemResponse;
import com.tailtopia.content.dto.FeedPageResponse;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

/**
 * L0：Feed 三项新下发**必须批量聚合**（V1.1.6 Story 3.1 · AD-7 Rule 2）。
 *
 * <p><b>这组测试守的是查询次数，不是查询结果。</b>
 * 「是否已赞」和「评论数」在仓库里都有现成的<b>逐条</b>方法（内容详情页在用），
 * 把它们搬进 {@code page.stream().map(...)} 里能跑通、结果也对 ——
 * <b>但每页 20 条就是 40 次查询</b>，而游标翻页越往后越慢，
 * 且这种退化<b>不会有任何测试变红</b>（结果是对的）。
 *
 * <p>所以这里直接断言：无论一页有多少条，相关查询都只发生<b>常数次</b>。
 */
class FeedBatchAggregationTest {

    private ContentPostRepository posts;
    private AccountQueryService accounts;
    private ContentLikeRepository likes;
    private CommentRepository comments;
    private FeedService service;

    /** V1.3.4 follow Story 2.1：卡片作者关注关系读端口。 */
    private com.tailtopia.social.read.FollowReader followReader =
            org.mockito.Mockito.mock(com.tailtopia.social.read.FollowReader.class);

    @BeforeEach
    void setUp() {
        posts = mock(ContentPostRepository.class);
        accounts = mock(AccountQueryService.class);
        likes = mock(ContentLikeRepository.class);
        comments = mock(CommentRepository.class);
        // V1.1.6 Story 4.2：只首屏让位要查顶置。这两类单测不验让位，给 mock（默认无顶置）。
        service = new FeedService(posts, accounts, likes, comments,
                Mockito.mock(ContentPinService.class),
                Mockito.mock(com.tailtopia.content.service.ContentTagQueryService.class),
                // V1.1.6 Story 4.4：顶置位隐藏过滤；本类只验批量取数，mock 默认 isHidden=false。
                Mockito.mock(com.tailtopia.social.read.UserHideRelationReader.class),
                // V1.1.6 Story 16.3：ALL Tab 走推荐序。本类验的是**组装那一步**的批量聚合，
                // 两条排序路径共用它（AD-7 Rule 4），所以用分类 Tab 验即可、不需要真的推荐序。
                Mockito.mock(com.tailtopia.content.rank.FeedRecommendationService.class),
                // V1.3.0 batch-b1 Story 3.3：@ 渲染投影。本类夹具都没有 @，
                // resolveAll 在碰任何依赖之前就返回空 Map，所以两个 mock 无需 stub
                // （也因此**不会**多发查询 —— 批量聚合的用例计数不受影响）。
                new com.tailtopia.mention.service.MentionViewService(
                        Mockito.mock(com.tailtopia.auth.service.AccountQueryService.class),
                        Mockito.mock(com.tailtopia.social.read.UserHideRelationReader.class)),
                // V1.3.4 follow Story 2.1：作者关注关系（整页一次批量）。
                followReader, com.tailtopia.content.service.PostMediaFixtures.passthrough());
        when(accounts.findAuthorViews(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().distinct().collect(Collectors.toMap(
                    id -> (Long) id,
                    id -> new AuthorView((Long) id, "u" + id, null, false, java.util.List.of())));
        });
    }

    /** 造一页 n 条内容。 */
    private void stubPage(int n) {
        List<ContentPost> rows = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            ContentPost p = ContentPost.publish(7L, ContentType.DAILY, null, "t" + i, null, null);
            setId(p, (long) i);
            rows.add(p);
        }
        when(posts.findFeed(any(), any(Boolean.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Boolean.class), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(Pageable.class)))
                .thenReturn(rows);
    }

    private static void setId(Object o, Long v) {
        try {
            var f = o.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(o, v);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 🛡 <b>本类最要紧的一条</b>：一页 20 条，两项新字段各只查<b>一次</b>。
     *
     * <p>若有人把逐条方法搬进循环，这里会立刻变成 20 次而变红。
     */
    @Test
    void newFieldsAreQueriedOncePerPageNotPerItem() {
        stubPage(20);

        service.loadFeed("DAILY", null, 99L, null, true);

        verify(likes, times(1)).findLikedPostIds(anyLong(), anyList());
        verify(comments, times(1)).countVisibleForViewerIn(anyList(), any());
        // 🛡 逐条方法一次都不该被碰 —— 它们是内容详情页的，搬进 Feed 就是 N+1。
        verify(likes, never()).existsByPostIdAndUserId(anyLong(), anyLong());
        verify(comments, never()).countVisibleForViewer(anyLong(), anyBoolean(), any(), anyLong());
    }

    /**
     * 🛡 未登录访客：已赞整批短路为 false，**不发这次查询**。
     *
     * <p>Feed 对游客开放，而游客不可能赞过任何东西 —— 白跑一次查询没有意义。
     */
    @Test
    void guestSkipsTheLikedQueryEntirely() {
        stubPage(5);

        FeedPageResponse page = service.loadFeed("DAILY", null, null, null, true);

        verify(likes, never()).findLikedPostIds(anyLong(), anyList());
        assertThat(page.items()).isNotEmpty();
        assertThat(page.items()).allSatisfy(i -> assertThat(i.liked()).isFalse());
    }

    /** 空页短路：一条都没有时不发任何聚合查询。 */
    @Test
    void emptyPageIssuesNoAggregationQueries() {
        stubPage(0);

        service.loadFeed("DAILY", null, 99L, null, true);

        verify(likes, never()).findLikedPostIds(anyLong(), anyList());
        verify(comments, never()).countVisibleForViewerIn(anyList(), any());
    }

    /** 「我的发布」走同一批聚合与同一个工厂（AD-7 Rule 4：口径不得分叉）。 */
    @Test
    void myPostsUsesTheSameBatchAggregation() {
        List<ContentPost> rows = new ArrayList<>();
        ContentPost p = ContentPost.publish(7L, ContentType.DAILY, null, "t", null, null);
        setId(p, 1L);
        rows.add(p);
        when(posts.findMyPosts(anyLong(), any(Boolean.class), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(Pageable.class)))
                .thenReturn(rows);

        service.myPosts(7L, null, true);

        verify(likes, times(1)).findLikedPostIds(anyLong(), anyList());
        verify(comments, times(1)).countVisibleForViewerIn(anyList(), any());
        verify(comments, never()).countVisibleForViewer(anyLong(), anyBoolean(), any(), anyLong());
    }

    /**
     * 🛡 响应里<b>只有原始宽高</b>，没有已收敛的比例、也没有算好的高度（AD-6 Rule 6）。
     *
     * <p>服务端先算一遍、客户端再算一遍 = 双重裁切。这条按字段名把它钉死。
     */
    @Test
    void responseCarriesRawSizesOnlyNoRatioNoHeight() {
        // ⚠️ 精确豁免：`decorationTags`（V1.1.6 Story 5.2 内容装饰标签）里恰好含有
        // "deco-RATIO-nTags" 这个子串，与本规则要防的"下发已算好的比例"毫无关系。
        // 放宽 hint（比如把 "ratio" 删掉）会让真正该拦的字段名溜过去，所以这里改用**逐个豁免**。
        var explicitlyAllowed = java.util.List.of("decorationtags");
        for (var rc : FeedItemResponse.class.getRecordComponents()) {
            String n = rc.getName().toLowerCase(java.util.Locale.ROOT);
            if (explicitlyAllowed.contains(n)) {
                continue;
            }
            assertThat(n)
                    .as("FeedItemResponse.%s 看起来在下发已算好的比例 / 高度 —— "
                            + "那些只能客户端算（护栏依赖可视区高度），服务端也算一遍就是双重裁切",
                            rc.getName())
                    .doesNotContain("ratio")
                    .doesNotContain("aspect")
                    .doesNotContain("displayheight")
                    .doesNotContain("clamped");
        }
    }

    // ===== V1.3.4 follow Story 2.1 · AC1.2 / AC1.3 =====

    /** 一页 20 条（多个作者）：作者 token 与关注关系各只查一次，且按作者去重。 */
    @Test
    void followStatesQueriedOncePerPage() {
        List<ContentPost> rows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            ContentPost p = ContentPost.publish(100L + (i % 4), ContentType.DAILY, null, "t" + i, null, null);
            setId(p, (long) i);
            rows.add(p);
        }
        when(posts.findFeed(any(), any(Boolean.class), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Boolean.class), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(Pageable.class)))
                .thenReturn(rows);
        when(accounts.publicTokensOf(anyList())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return ids.stream().collect(Collectors.toMap(id -> id, id -> "tok" + id));
        });
        when(followReader.followStates(anyLong(), anyList())).thenReturn(java.util.Map.of(
                101L, new com.tailtopia.social.read.FollowState(true, false),
                102L, new com.tailtopia.social.read.FollowState(false, true)));

        FeedPageResponse page = service.loadFeed("DAILY", null, 99L, null, true);

        verify(accounts, times(1)).publicTokensOf(anyList());
        verify(followReader, times(1)).followStates(org.mockito.ArgumentMatchers.eq(99L), anyList());
        verify(followReader, never()).followState(anyLong(), anyLong());
        assertThat(page.items()).allSatisfy(item -> {
            assertThat(item.authorToken()).isEqualTo("tok" + item.authorId());
            assertThat(item.authorFollowState()).isNotNull();
        });
        assertThat(page.items()).filteredOn(i -> i.authorId() == 101L)
                .allSatisfy(i -> assertThat(i.authorFollowState().iFollow()).isTrue());
        assertThat(page.items()).filteredOn(i -> i.authorId() == 103L)
                .allSatisfy(i -> assertThat(i.authorFollowState())
                        .isEqualTo(com.tailtopia.social.read.FollowState.NONE));
    }

    /** 游客：不查关注关系，恒 false；空页：两个查询都不发。 */
    @Test
    void guestAndEmptyPageSkipFollowQueries() {
        stubPage(5);
        when(accounts.publicTokensOf(anyList())).thenReturn(java.util.Map.of(7L, "tok7"));

        FeedPageResponse page = service.loadFeed("DAILY", null, null, null, true);

        verify(followReader, never()).followStates(anyLong(), anyList());
        assertThat(page.items()).allSatisfy(i -> assertThat(i.authorFollowState())
                .isEqualTo(com.tailtopia.social.read.FollowState.NONE));

        org.mockito.Mockito.clearInvocations(followReader);
        stubPage(0);
        service.loadFeed("DAILY", null, 99L, null, true);
        verify(followReader, never()).followStates(anyLong(), anyList());
    }

    /** 本人的帖子（含本人待审帖）与封号 / 注销作者（无 token）不查关注关系、不下发 token（D-8）。 */
    @Test
    void selfAndInactiveAuthorsGetNoFollowLookup() {
        stubPage(3); // 作者恒为 7
        when(accounts.publicTokensOf(anyList())).thenReturn(java.util.Map.of());

        FeedPageResponse page = service.loadFeed("DAILY", null, 99L, null, true);
        verify(followReader, never()).followStates(anyLong(), anyList());
        assertThat(page.items()).allSatisfy(i -> assertThat(i.authorToken()).isNull());

        when(accounts.publicTokensOf(anyList())).thenReturn(java.util.Map.of(7L, "tok7"));
        page = service.loadFeed("DAILY", null, 7L, null, true);
        verify(followReader, never()).followStates(anyLong(), anyList());
        assertThat(page.items()).allSatisfy(i -> assertThat(i.authorFollowState())
                .isEqualTo(com.tailtopia.social.read.FollowState.NONE));
    }

    /** 「我的发布」不展示关注按钮：token / 关注关系都不查、不下发。 */
    @Test
    void myPostsCarryNoFollowFields() {
        List<ContentPost> rows = new ArrayList<>();
        ContentPost p = ContentPost.publish(7L, ContentType.DAILY, null, "t", null, null);
        setId(p, 1L);
        rows.add(p);
        when(posts.findMyPosts(anyLong(), any(Boolean.class), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(Pageable.class)))
                .thenReturn(rows);

        FeedPageResponse page = service.myPosts(7L, null, true);

        verify(accounts, never()).publicTokensOf(anyList());
        verify(followReader, never()).followStates(anyLong(), anyList());
        assertThat(page.items().get(0).authorToken()).isNull();
        assertThat(page.items().get(0).authorFollowState()).isNull();
    }
}
