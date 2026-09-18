package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
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
import org.springframework.data.jpa.repository.Query;

/**
 * L0：他人公开主页的**内容区**（V1.3.0 batch-b1 Story 2.2 · FR-118.2 · AC1/AC3）。
 *
 * <p>两组断言，各守一条 AC：
 * <ul>
 *   <li><b>AC1 可见范围</b> —— 谓词写在 JPQL 里，DB 才跑得到，所以这里**直接断言那串 JPQL**
 *       （把 {@code visibility = PUBLIC} 删掉是一个跑通、结果"看着也对"、
 *       却把私密内容对外放出去的改动，不这么钉就没有任何测试会红）；</li>
 *   <li><b>AC3 无 N+1</b> —— 断言的是**查询次数**不随页大小增长，不是查询结果。</li>
 * </ul>
 */
class UserPublicPostsTest {

    private static final long AUTHOR = 9L;

    private ContentPostRepository posts;
    private AccountQueryService accounts;
    private ContentLikeRepository likes;
    private CommentRepository comments;
    private FeedService service;

    @BeforeEach
    void setUp() {
        posts = mock(ContentPostRepository.class);
        accounts = mock(AccountQueryService.class);
        likes = mock(ContentLikeRepository.class);
        comments = mock(CommentRepository.class);
        service = new FeedService(posts, accounts, likes, comments,
                Mockito.mock(ContentPinService.class),
                Mockito.mock(ContentTagQueryService.class),
                Mockito.mock(com.tailtopia.social.read.UserHideRelationReader.class),
                Mockito.mock(com.tailtopia.content.rank.FeedRecommendationService.class),
                // V1.3.0 batch-b1 Story 3.3：@ 渲染投影。本类夹具都没有 @，
                // resolveAll 在碰任何依赖之前就返回空 Map，所以两个 mock 无需 stub
                // （也因此**不会**多发查询 —— 批量聚合的用例计数不受影响）。
                new com.tailtopia.mention.service.MentionViewService(
                        Mockito.mock(com.tailtopia.auth.service.AccountQueryService.class),
                        Mockito.mock(com.tailtopia.social.read.UserHideRelationReader.class)));
        when(accounts.findAuthorViews(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().distinct().collect(Collectors.toMap(
                    id -> (Long) id,
                    id -> new AuthorView((Long) id, "u" + id, null, false, List.of())));
        });
    }

    // ===== AC1：可见范围在服务端判定（谓词写在 JPQL 里） =====

    /**
     * 🔴 {@code visibility = PUBLIC} 必须在查询里。
     *
     * <p>删掉它的改动**编译通过、接口有数、页面看着也正常** —— 只是这个人的私密日记
     * 从此挂在他的公开主页上。客户端过滤不算数：那只是"看不见"，抓包照样拿得到（NFR-2）。
     */
    @Test
    void thePublicPostsQueryFiltersByVisibilityOnTheServer() throws Exception {
        assertThat(publicPostsJpql())
                .as("NFR-2：非 PUBLIC 的内容服务端就不该查出来")
                .contains("p.visibility = com.tailtopia.content.domain.ContentVisibility.PUBLIC");
    }

    /**
     * 🔴 **挂起帖不进他人主页**。
     *
     * <p>「我的发布」那条查询是 {@code PUBLISHED OR UNDER_REVIEW}（作者自视，看得到自己审核中的）。
     * 照抄过来就等于把还没过审的内容提前对外放出去。
     */
    @Test
    void thePublicPostsQueryDoesNotLeakUnderReviewPosts() throws Exception {
        assertThat(publicPostsJpql())
                .as("挂起帖仅作者本人可见")
                .doesNotContain("UNDER_REVIEW");
    }

    /**
     * 🔴 访客**自己隐藏过的**同样不进网格：举报过的那一条（{@code ContentReport}）、
     * 隐藏过的那个人（{@code UserHideRelation}，不分 BLOCK / REPORT）。
     *
     * <p>不排的话，网格里会摆出一排**点进去全是 404** 的死格子 ——
     * {@code ContentDetailService} 对这两种情况一律 404（PRD §7 消灭的语义倒挂）。
     */
    @Test
    void thePublicPostsQueryExcludesWhatTheViewerHasHidden() throws Exception {
        String jpql = publicPostsJpql();
        assertThat(jpql)
                .as("访客举报过的那一条帖")
                .contains("FROM ContentReport r")
                .contains("r.reporterId = :viewerId");
        assertThat(jpql)
                .as("访客隐藏过的那个人（不分 BLOCK / REPORT）")
                .contains("FROM UserHideRelation h")
                .contains("h.holderId = :viewerId");
        // ⚠️ 门控走 hasViewer 布尔，不是 `:viewerId IS NULL` —— 游客传 NULL 会触发 42P18。
        assertThat(jpql).contains(":hasViewer = false");
    }

    /** 软删的不出现；三类混排不分流（查询里没有 type 过滤）。 */
    @Test
    void thePublicPostsQuerySkipsSoftDeletedAndDoesNotSplitByType() throws Exception {
        String jpql = publicPostsJpql();
        assertThat(jpql).contains("p.deletedAt IS NULL");
        assertThat(jpql).as("Diary / Moment / Tips 混排不分流（AC1）").doesNotContain("p.type");
        assertThat(jpql).as("时间倒序").contains("ORDER BY p.createdAt DESC, p.id DESC");
    }

    /** 发帖总数与获赞总数的口径，必须与上面那条列表查询同源。 */
    @Test
    void bothCountsShareTheSameVisibilityScopeAsTheGrid() throws Exception {
        String countPosts = jpqlOf(ContentPostRepository.class, "countPublicPublishedByAuthor");
        String sumLikes = jpqlOf(ContentLikeRepository.class, "sumLikesOnPublicPostsByAuthor");
        for (String q : List.of(countPosts, sumLikes)) {
            assertThat(q)
                    .as("计数与网格口径不同源的话，差值本身就是一条可推断的私密信息")
                    .contains("ContentVisibility.PUBLIC")
                    .contains("PostStatus.PUBLISHED")
                    .contains("deletedAt IS NULL");
        }
    }

    // ===== AC3：一次批量取齐，无 N+1 =====

    /**
     * <b>这组断言守的是查询次数，不是查询结果。</b>
     *
     * <p>把逐条方法搬进 {@code page.stream().map(...)} 能跑通、结果也对 ——
     * 但一页 20 条就是 40 次查询，而且**不会有任何测试变红**。
     */
    @Test
    void authorsLikesAndCommentsAreEachFetchedOncePerPage() {
        stubPage(20);

        service.userPublicPosts(AUTHOR, 5L, null);

        verify(accounts, times(1)).findAuthorViews(anyList());
        verify(likes, times(1)).countByPostIdIn(anyList());
        verify(likes, times(1)).findLikedPostIds(anyLong(), anyList());
        verify(comments, times(1)).countVisibleForViewerIn(anyList(), any());
        // 逐条方法一次都不许出现在这条路径上。
        verify(likes, never()).countByPostId(anyLong());
        verify(likes, never()).existsByPostIdAndUserId(anyLong(), anyLong());
    }

    /** 游客：整批短路，不问「我赞过没」（未登录不该查到这一步）。 */
    @Test
    void aGuestNeverAsksWhichPostsTheyLiked() {
        stubPage(5);

        service.userPublicPosts(AUTHOR, null, null);

        verify(likes, never()).findLikedPostIds(anyLong(), anyList());
    }

    /** 空页不发任何批量查询（一个没发过帖的人，别白打四次库）。 */
    @Test
    void anEmptyPageIssuesNoBatchQueriesAtAll() {
        stubPage(0);

        FeedPageResponse page = service.userPublicPosts(AUTHOR, 5L, null);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        verify(likes, never()).countByPostIdIn(anyList());
        verify(comments, never()).countVisibleForViewerIn(anyList(), any());
    }

    // ===== 分页 =====

    /** 满一页 + 还有下一页 → 截到 PAGE_SIZE，并给出下一页游标。 */
    @Test
    void aFullPageIsTrimmedAndCarriesTheNextCursor() {
        int pageSize = stubOverflowingPage();

        FeedPageResponse page = service.userPublicPosts(AUTHOR, 5L, null);

        assertThat(page.items()).hasSize(pageSize);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        // 游标能解回最后一条（翻页不靠 offset，避免新帖插入导致错位/重复）。
        FeedCursor decoded = FeedCursor.decode(page.nextCursor());
        assertThat(decoded.id()).isEqualTo(pageSize);
    }

    /** 带游标进来 → hasCursor=true 且把游标的两个分量原样带给查询。 */
    @Test
    void anIncomingCursorIsDecodedAndPassedToTheQuery() {
        stubPage(0);
        Instant ts = Instant.parse("2026-09-01T10:00:00Z");

        service.userPublicPosts(AUTHOR, 5L, new FeedCursor(ts, 42L).encode());

        verify(posts).findPublicPostsByAuthor(eq(AUTHOR), eq(true), eq(5L), eq(true), eq(ts),
                eq(42L), any(Pageable.class));
    }

    /** 🔴 访客 id 必须**带进查询**（上面那两条排除全靠它）；游客则整段关掉。 */
    @Test
    void theViewerIsHandedToTheQuerySoTheExclusionsCanRun() {
        stubPage(0);

        service.userPublicPosts(AUTHOR, 5L, null);
        verify(posts).findPublicPostsByAuthor(eq(AUTHOR), eq(true), eq(5L), eq(false), any(),
                any(), any(Pageable.class));

        service.userPublicPosts(AUTHOR, null, null);
        verify(posts).findPublicPostsByAuthor(eq(AUTHOR), eq(false), isNull(), eq(false), any(),
                any(), any(Pageable.class));
    }

    /**
     * ⚠️ 主页不是 Feed 出口，{@code rankMode} 必须省略。
     *
     * <p>给它填个 {@code chrono}，埋点侧就会把主页的浏览算进首页排序的分母里（同「我的发布」）。
     */
    @Test
    void theProfileGridIsNotAFeedOutletSoItCarriesNoRankMode() {
        stubPage(3);

        assertThat(service.userPublicPosts(AUTHOR, 5L, null).rankMode()).isNull();
    }

    // ===== helpers =====

    private void stubPage(int n) {
        when(posts.findPublicPostsByAuthor(anyLong(), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Pageable.class))).thenReturn(rows(n));
    }

    /** 造出「比一页多一条」，用来验截断与游标。返回 PAGE_SIZE。 */
    private int stubOverflowingPage() {
        int pageSize = FeedService.PAGE_SIZE;
        when(posts.findPublicPostsByAuthor(anyLong(), any(Boolean.class), any(),
                any(Boolean.class), any(), any(), any(Pageable.class)))
                .thenReturn(rows(pageSize + 1));
        return pageSize;
    }

    private static List<ContentPost> rows(int n) {
        List<ContentPost> rows = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            ContentPost p = ContentPost.publish(AUTHOR, ContentType.DAILY, null, "t" + i, null, null);
            setId(p, (long) i);
            setCreatedAt(p, Instant.parse("2026-09-01T10:00:00Z").minusSeconds(i));
            rows.add(p);
        }
        return rows;
    }

    private static void setId(ContentPost p, Long v) {
        setField(p, "id", v);
    }

    /** {@code createdAt} 由 {@code @PrePersist} 填，单测里不会跑 —— 游标要用到它。 */
    private static void setCreatedAt(ContentPost p, Instant v) {
        setField(p, "createdAt", v);
    }

    private static void setField(ContentPost p, String name, Object v) {
        try {
            var f = ContentPost.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(p, v);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ContentPost." + name + " 字段名变了，改这里", e);
        }
    }

    private static String publicPostsJpql() throws Exception {
        return jpqlOf(ContentPostRepository.class, "findPublicPostsByAuthor");
    }

    /** 取某个仓库方法上 {@code @Query} 的 JPQL 原文（谓词只有 DB 跑得到，L0 只能这么钉）。 */
    private static String jpqlOf(Class<?> repo, String method) throws Exception {
        for (var m : repo.getDeclaredMethods()) {
            if (m.getName().equals(method)) {
                Query q = m.getAnnotation(Query.class);
                assertThat(q).as(method + " 应当带 @Query").isNotNull();
                return q.value();
            }
        }
        throw new AssertionError(repo.getSimpleName() + " 上找不到方法 " + method);
    }
}
