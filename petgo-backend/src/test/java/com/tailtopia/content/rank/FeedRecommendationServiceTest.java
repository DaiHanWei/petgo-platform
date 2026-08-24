package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentTagQueryService;
import com.tailtopia.content.species.ContentSpeciesResolver;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L0：推荐序取数与翻页（Story 16.3）。
 *
 * <p>引擎用<b>真实现</b>（它是纯函数），只把取数与缓存 mock 掉 —— 这样验的是「喂数据与切页」
 * 这一层，而不是把排序结果又断言一遍（那是 16.2 的事）。
 */
class FeedRecommendationServiceTest {

    private ContentPostRepository posts;
    private ContentLikeRepository likes;
    private CommentRepository comments;
    private ContentTagQueryService tags;
    private ContentSpeciesResolver species;
    private PetProfileRepository pets;
    private FeedSeenStore seen;
    private FeedSequenceStore sequences;
    private FeedRecommendationService service;

    @BeforeEach
    void setUp() {
        posts = mock(ContentPostRepository.class);
        likes = mock(ContentLikeRepository.class);
        comments = mock(CommentRepository.class);
        tags = mock(ContentTagQueryService.class);
        species = mock(ContentSpeciesResolver.class);
        pets = mock(PetProfileRepository.class);
        seen = mock(FeedSeenStore.class);
        sequences = mock(FeedSequenceStore.class);

        when(likes.countByPostIdIn(anyList())).thenReturn(List.of());
        when(comments.countVisibleForViewerIn(anyList(), any())).thenReturn(List.of());
        when(tags.findVisibleTags(any(), any())).thenReturn(Map.of());
        when(species.resolveAll(anyList())).thenReturn(Map.of());
        when(pets.findByOwnerId(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Optional.empty());
        when(seen.decayFactors(any(), anyList(), any())).thenReturn(Map.of());
        when(sequences.newSeed(any())).thenReturn("s-seed-1");
        // 默认无挂起帖（AC2 那条单独用例自己打桩）
        when(posts.findOwnPendingPosts(org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(List.of());

        service = new FeedRecommendationService(posts, likes, comments, tags, species, pets,
                seen, sequences, new FeedRankEngine(),
                new FeedRankProperties(0.3, 7, 30, 100, 1000));
    }

    private static ContentPost post(long id, long authorId) {
        ContentPost p = ContentPost.publish(authorId, ContentType.DAILY, null, "t" + id, List.of());
        try {
            var f = ContentPost.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
            var c = ContentPost.class.getDeclaredField("createdAt");
            c.setAccessible(true);
            c.set(p, Instant.parse("2026-08-24T10:00:00Z").minusSeconds(id));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    /** 序列里有这些 id，且全部仍合格。 */
    private void sequenceIs(List<Long> ids) {
        sequenceIs(ids, id -> true);
    }

    /**
     * 序列里有这些 id，{@code stillAlive} 决定哪些读回来时仍合格。
     *
     * <p>⚠️ 「哪些合格」做成参数而不是让用例二次 {@code when(...)} 覆盖 —— 那样会踩 Mockito 的坑：
     * {@code when(mock.f(any()))} 会<b>真的调一次 mock</b> 来记录调用，
     * 此时上一个 answer 会带着 null 参数执行，直接 NPE。
     */
    private void sequenceIs(List<Long> ids, java.util.function.LongPredicate stillAlive) {
        when(sequences.length(any(), eq("s-seed-1"))).thenReturn((long) ids.size());
        when(sequences.read(any(), eq("s-seed-1"), org.mockito.ArgumentMatchers.anyLong(), anyInt()))
                .thenAnswer(inv -> {
                    long off = inv.getArgument(2);
                    int lim = inv.getArgument(3);
                    if (off >= ids.size()) {
                        return List.of();
                    }
                    return ids.subList((int) off, Math.min((int) off + lim, ids.size()));
                });
        when(posts.findRankableByIds(any(), anyBoolean(), any())).thenAnswer(inv -> {
            Collection<Long> asked = inv.getArgument(0);
            List<ContentPost> out = new ArrayList<>();
            if (asked == null) {
                return out;
            }
            for (Long id : asked) {
                if (stillAlive.test(id)) {
                    out.add(post(id, id));
                }
            }
            return out;
        });
    }

    // ── 🔴 从快照读回来的 id 必须重新过一遍过滤（AC4） ─────────────────

    /**
     * 🔴 <b>本类最要紧的一条</b>：快照有 30 分钟寿命，期间内容可能被删 / 被挂起 / 转私密，
     * 或查看者刚拉黑了某个作者。所以读页必须走带过滤的查询，且被过滤掉的要<b>从序列里补齐</b>，
     * 而不是返回一个短页（短页会被客户端理解成「到底了」）。
     */
    @Test
    void staleSequenceEntriesAreRefilteredAndBackfilled() {
        List<Long> seq = new ArrayList<>();
        for (long i = 1; i <= 40; i++) {
            seq.add(i);
        }
        sequenceIs(seq, id -> id > 5); // id 1..5 已不合格（被删 / 被拉黑…）

        FeedRecommendationService.RankedPage page =
                service.page(9L, null, null, 20, null);

        assertThat(page.posts()).hasSize(20); // 🛡 补齐到满页，不是 15 条
        assertThat(page.posts().stream().map(ContentPost::getId))
                .doesNotContain(1L, 2L, 3L, 4L, 5L);
    }

    /**
     * 🔴 <b>过滤参数必须真的传到那个查询上</b>。
     *
     * <p>上一条只能证明「读回来的东西被过滤了」，证不了<b>按谁过滤</b>：
     * 把 {@code hasViewer/viewerId} 写成 {@code false/null}，查询照样能跑、页照样满，
     * 但举报者隐藏与账号级隐藏两条子查询会被整条短路 —— <b>拉黑白拉</b>，且没有任何报错。
     * 所以这里直接钉调用点的实参。
     */
    @Test
    void viewerScopedFiltersAreActuallyPassedToTheQuery() {
        sequenceIs(List.of(1L, 2L));

        service.page(42L, null, null, 20, null);

        verify(posts).findRankableByIds(any(), eq(true), eq(42L));
    }

    /** 游客：两条按查看者的过滤不适用，{@code hasViewer=false}（不是传个假的 viewerId）。 */
    @Test
    void guestPassesNoViewerToTheQuery() {
        sequenceIs(List.of(1L, 2L));

        service.page(null, "anon", null, 20, null);

        verify(posts).findRankableByIds(any(), eq(false), org.mockito.ArgumentMatchers.isNull());
    }

    /** ⚠️ 被丢弃的条目<b>也算消费掉了</b> —— 下一页不能再从那些位置读起。 */
    @Test
    void discardedEntriesStillAdvanceTheCursor() {
        List<Long> seq = new ArrayList<>();
        for (long i = 1; i <= 60; i++) {
            seq.add(i);
        }
        sequenceIs(seq, id -> id > 5);

        FeedRecommendationService.RankedPage p1 = service.page(9L, null, null, 20, null);
        FeedRankCursor c = FeedRankCursor.decode(p1.nextCursor());

        // 25 = 20 条展示 + 5 条被丢弃；用「页码 × 20」会得到 20，下一页就会重复读到 21..25
        assertThat(c.consumed()).isEqualTo(25);
        assertThat(c.seed()).isEqualTo("s-seed-1");
    }

    /** 🔴 翻页不重复：连续三页的 id 互不相交。 */
    @Test
    void pagesDoNotOverlap() {
        List<Long> seq = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            seq.add(i);
        }
        sequenceIs(seq);

        List<Long> all = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 3; i++) {
            FeedRecommendationService.RankedPage p = service.page(9L, null, cursor, 20, null);
            all.addAll(p.posts().stream().map(ContentPost::getId).toList());
            cursor = p.nextCursor();
        }
        assertThat(all).hasSize(60);
        assertThat(all).doesNotHaveDuplicates();
    }

    // ── AC2：作者本人的挂起帖 ───────────────────────────────────────

    /**
     * 🔴 挂起帖首屏置顶，且<b>不占算法槽位、不参与配比与打分</b>。
     *
     * <p>让它进候选池是很自然的写法（时间倒序那条路径就是那么写的），但那会让作者自己的
     * 待审内容去和全平台内容<b>抢分数</b> —— 抢不到就等于「刚发的帖从首页消失」，
     * 而作者只会以为发布失败了。
     */
    @Test
    void ownPendingPostIsPinnedToTheFirstPageAndNeverScored() {
        List<Long> seq = new ArrayList<>();
        for (long i = 1; i <= 40; i++) {
            seq.add(i);
        }
        sequenceIs(seq);
        when(posts.findOwnPendingPosts(eq(9L), any())).thenReturn(List.of(post(999L, 9L)));

        FeedRecommendationService.RankedPage p = service.page(9L, null, null, 20, null);

        assertThat(p.posts().get(0).getId()).as("挂起帖应在首屏最前").isEqualTo(999L);
        assertThat(p.posts()).hasSize(20); // 页大小不变
        // 🛡 它没进候选池 ⇒ 引擎压根没见过它（候选池查询只收 PUBLISHED，这里以"没被当成序列项"体现）
        assertThat(p.posts().stream().skip(1).map(ContentPost::getId)).doesNotContain(999L);
    }

    /** 🛡 只首屏置顶；后续页不再出现（序列里本来就没有它）。 */
    @Test
    void ownPendingPostDoesNotRepeatOnLaterPages() {
        List<Long> seq = new ArrayList<>();
        for (long i = 1; i <= 60; i++) {
            seq.add(i);
        }
        sequenceIs(seq);
        when(posts.findOwnPendingPosts(eq(9L), any())).thenReturn(List.of(post(999L, 9L)));

        String cursor = new FeedRankCursor("s-seed-1", 20).encode();
        FeedRecommendationService.RankedPage later = service.page(9L, null, cursor, 20, null);

        assertThat(later.posts().stream().map(ContentPost::getId)).doesNotContain(999L);
    }

    /**
     * 🔴 <b>排序后才被挂起</b>的那条不能出现两次。
     *
     * <p>一条帖排序时还是 PUBLISHED、之后被挂起 ⇒ 它既在序列里、又会被"本人挂起帖"取到，
     * 而按 id 读回来的查询允许作者看自己的挂起帖 —— 不去重就是首屏同一条出现两次。
     */
    @Test
    void aPostHeldAfterRankingIsNotShownTwice() {
        sequenceIs(List.of(5L, 6L, 7L));
        when(posts.findOwnPendingPosts(eq(9L), any())).thenReturn(List.of(post(5L, 9L)));

        FeedRecommendationService.RankedPage p = service.page(9L, null, null, 20, null);

        assertThat(p.posts().stream().map(ContentPost::getId)).doesNotHaveDuplicates();
        assertThat(p.posts().stream().map(ContentPost::getId)).containsExactly(5L, 6L, 7L);
    }

    /** 🛡 挂起帖不记曝光（它没参与打分，记了只会占着曝光集合）。 */
    @Test
    void ownPendingPostIsNotMarkedAsSeen() {
        sequenceIs(List.of(1L, 2L));
        when(posts.findOwnPendingPosts(eq(9L), any())).thenReturn(List.of(post(999L, 9L)));

        service.page(9L, null, null, 20, null);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Collection<Long>> captor =
                org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(seen).markSeen(any(), captor.capture(), any());
        assertThat(captor.getValue()).containsExactly(1L, 2L).doesNotContain(999L);
    }

    /** 游客没有"本人挂起帖"这回事，压根不该发这次查询。 */
    @Test
    void guestNeverQueriesPendingPosts() {
        sequenceIs(List.of(1L));

        service.page(null, "anon", null, 20, null);

        verify(posts, never()).findOwnPendingPosts(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    // ── 顶置首屏让位（沿用 Story 4.2 口径） ──────────────────────────

    /** 🛡 顶置的那条不在第一页出现；后续页不让位。 */
    @Test
    void pinnedContentIsSkippedOnFirstPageOnly() {
        List<Long> seq = new ArrayList<>();
        for (long i = 1; i <= 60; i++) {
            seq.add(i);
        }
        sequenceIs(seq);

        FeedRecommendationService.RankedPage first = service.page(9L, null, null, 20, 3L);
        assertThat(first.posts().stream().map(ContentPost::getId)).doesNotContain(3L);

        // 后续页（带游标）不让位 —— 传同一个 yieldId 也不生效
        String cursor = new FeedRankCursor("s-seed-1", 0).encode();
        FeedRecommendationService.RankedPage later = service.page(9L, null, cursor, 20, 3L);
        assertThat(later.posts().stream().map(ContentPost::getId)).contains(3L);
    }

    // ── 曝光记录（16.1 AC2：下发即记） ──────────────────────────────

    @Test
    void servedContentIsMarkedAsSeen() {
        sequenceIs(List.of(1L, 2L, 3L));

        service.page(9L, null, null, 20, null);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Collection<Long>> captor =
                org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(seen).markSeen(any(), captor.capture(), any());
        assertThat(captor.getValue()).containsExactly(1L, 2L, 3L);
    }

    /** 游客用匿名会话 id 作缓存键（曝光记录本身在 FeedSeenStore 里对游客 no-op）。 */
    @Test
    void guestUsesAnonSessionKeyspace() {
        sequenceIs(List.of(1L));

        service.page(null, "abc", null, 20, null);

        org.mockito.ArgumentCaptor<FeedRankCacheKey> key =
                org.mockito.ArgumentCaptor.forClass(FeedRankCacheKey.class);
        verify(seen).markSeen(key.capture(), any(), any());
        assertThat(key.getValue().guest()).isTrue();
        assertThat(key.getValue().namespace()).isEqualTo("a:abc");
    }

    // ── AC5：批量取赞评 ─────────────────────────────────────────────

    /** 🛡 生成一次序列 → 赞/评各只查<b>一次</b>（禁止逐条 COUNT）。 */
    @Test
    void countsAreBatchedOncePerGeneration() {
        // 快照为空 ⇒ 需要生成
        when(sequences.length(any(), any())).thenReturn(0L);
        when(sequences.read(any(), any(), org.mockito.ArgumentMatchers.anyLong(), anyInt()))
                .thenReturn(List.of());
        List<ContentPost> pool = new ArrayList<>();
        for (long i = 1; i <= 50; i++) {
            pool.add(post(i, i));
        }
        when(posts.findRankCandidatePool(anyBoolean(), any(), any())).thenReturn(pool);
        when(posts.findRankableByIds(any(), anyBoolean(), any())).thenAnswer(inv -> {
            Collection<Long> asked = inv.getArgument(0);
            List<ContentPost> out = new ArrayList<>();
            for (Long id : asked) {
                out.add(post(id, id));
            }
            return out;
        });

        service.page(9L, null, null, 20, null);

        verify(likes, times(1)).countByPostIdIn(anyList());
        verify(comments, times(1)).countVisibleForViewerIn(anyList(), any());
        // 🛡 逐条方法一次都不该被碰
        verify(likes, never()).existsByPostIdAndUserId(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * 🛡 <b>Redis 不可用（降级链级别 3）仍要出内容</b>：{@code length} 恒 0、{@code read} 恒空
     * ⇒ 每次请求实时算一遍。接受翻页可能重复，<b>但绝不返回空页或报错</b>。
     */
    @Test
    void worksWhenTheSnapshotIsUnavailable() {
        when(sequences.length(any(), any())).thenReturn(0L);
        when(sequences.read(any(), any(), org.mockito.ArgumentMatchers.anyLong(), anyInt()))
                .thenReturn(List.of());
        List<ContentPost> pool = new ArrayList<>();
        for (long i = 1; i <= 50; i++) {
            pool.add(post(i, i));
        }
        when(posts.findRankCandidatePool(anyBoolean(), any(), any())).thenReturn(pool);
        when(posts.findRankableByIds(any(), anyBoolean(), any())).thenAnswer(inv -> {
            Collection<Long> asked = inv.getArgument(0);
            List<ContentPost> out = new ArrayList<>();
            for (Long id : asked) {
                out.add(post(id, id));
            }
            return out;
        });

        FeedRecommendationService.RankedPage p = service.page(9L, null, null, 20, null);

        assertThat(p.posts()).isNotEmpty();
    }

    /** 空池 → 空页 + 无游标，不抛错。 */
    @Test
    void emptyPoolYieldsEmptyPage() {
        when(sequences.length(any(), any())).thenReturn(0L);
        when(sequences.read(any(), any(), org.mockito.ArgumentMatchers.anyLong(), anyInt()))
                .thenReturn(List.of());
        when(posts.findRankCandidatePool(anyBoolean(), any(), any())).thenReturn(List.of());

        FeedRecommendationService.RankedPage p = service.page(9L, null, null, 20, null);

        assertThat(p.posts()).isEmpty();
        assertThat(p.nextCursor()).isNull();
        assertThat(p.hasMore()).isFalse();
    }

    /** 游标非法 → 422（不静默当成首屏，否则表现为「下拉刷新后又从头开始」）。 */
    @Test
    void invalidCursorIsRejected() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.page(9L, null, "!!!not-base64!!!", 20, null))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class);
    }

    /** 🛡 未使用的入参不会被当成筛选条件：登录用户忽略匿名会话 id。 */
    @Test
    void loggedInViewerIgnoresAnonSessionId() {
        sequenceIs(List.of(1L));

        service.page(9L, "some-anon", null, 20, null);

        org.mockito.ArgumentCaptor<FeedRankCacheKey> key =
                org.mockito.ArgumentCaptor.forClass(FeedRankCacheKey.class);
        verify(seen).markSeen(key.capture(), any(), any());
        assertThat(key.getValue().namespace()).isEqualTo("u:9");
        assertThat(key.getValue().guest()).isFalse();
    }
}
