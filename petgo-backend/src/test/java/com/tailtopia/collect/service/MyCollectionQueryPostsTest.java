package com.tailtopia.collect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.collect.domain.ContentCollection;
import com.tailtopia.collect.dto.CollectedPostRow;
import com.tailtopia.collect.dto.CollectionPage;
import com.tailtopia.collect.repository.ContentCollectionRepository;
import com.tailtopia.collect.repository.PlaceCollectionRepository;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.FeedItemResponse;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.PostMediaResolver;
import com.tailtopia.place.service.PlaceQueryService;
import com.tailtopia.shared.paging.KeysetCursor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：V1.4.0 ④ Story 2.2 · AC2 / AC3 / AC4 / AC9 —— 我的帖子收藏列表组装（批量、零 N+1）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MyCollectionQueryPostsTest {

    @Mock ContentCollectionRepository contentCollections;
    @Mock PlaceCollectionRepository placeCollections;
    @Mock ContentPostRepository posts;
    @Mock CollectVisibility visibility;
    @Mock AccountQueryService accounts;
    @Mock PlaceQueryService placeCards;
    @Mock PostMediaResolver postMedia;

    private static final long ME = 7L;
    private static final Instant T0 = Instant.parse("2026-10-18T05:00:00.123456Z");

    private MyCollectionQuery query() {
        return new MyCollectionQuery(contentCollections, placeCollections, posts, visibility, accounts, placeCards, postMedia);
    }

    private static ContentCollection row(long id, long postId, Instant at) {
        ContentCollection c = mock(ContentCollection.class);
        lenient().when(c.getId()).thenReturn(id);
        lenient().when(c.getPostId()).thenReturn(postId);
        lenient().when(c.getCreatedAt()).thenReturn(at);
        lenient().when(c.getUserId()).thenReturn(ME);
        return c;
    }

    private static ContentPost post(long id, long authorId, String text, List<String> images) {
        ContentPost p = ContentPost.publish(authorId, ContentType.DAILY, null, text, images);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    /** n 条收藏行（id 递减 = 时间倒序），帖子 i 的作者 100+i。 */
    private void seed(int n, Set<Long> visibleIds) {
        List<ContentCollection> rows = new ArrayList<>();
        List<ContentPost> ps = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            rows.add(row(1000 - i, i, T0.minusSeconds(i)));
            ps.add(post(i, 100 + i, "text " + i, List.of("https://cdn/" + i + ".jpg")));
        }
        when(contentCollections.findPage(eq(ME), any(), anyLong(), any(Pageable.class))).thenReturn(rows);
        when(posts.findAllById(any())).thenReturn(ps);
        when(visibility.visiblePostIds(eq(ME), any())).thenReturn(visibleIds);
        when(accounts.findAuthorViewsWithoutTags(any())).thenAnswer(inv -> {
            Map<Long, AuthorView> m = new HashMap<>();
            for (Long a : (Collection<Long>) inv.getArgument(0)) {
                m.put(a, new AuthorView(a, "Rina" + a, "https://cdn/av" + a + ".jpg", false, List.of()));
            }
            return m;
        });
    }

    @Test
    void invisibleRowsKeepPlaceButHaveNoPost() {
        seed(3, Set.of(1L, 3L));
        CollectionPage<CollectedPostRow> page = query().posts(ME, null, true);

        assertThat(page.items()).extracting(CollectedPostRow::postId).containsExactly(1L, 2L, 3L);
        CollectedPostRow hidden = page.items().get(1);
        assertThat(hidden.available()).isFalse();
        assertThat(hidden.post()).isNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> authors = ArgumentCaptor.forClass(Collection.class);
        verify(accounts).findAuthorViewsWithoutTags(authors.capture());
        assertThat(authors.getValue()).containsExactlyInAnyOrder(101L, 103L);
        CollectedPostRow first = page.items().get(0);
        assertThat(first.available()).isTrue();
        assertThat(first.post().type()).isEqualTo(ContentType.DAILY);
        assertThat(first.post().author().nickname()).isEqualTo("Rina101");
        assertThat(first.collectedAt()).isEqualTo(T0.minusSeconds(1));
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void missingPostIsInvisible() {
        ContentCollection r = row(5L, 99L, T0);
        when(contentCollections.findPage(eq(ME), any(), anyLong(), any(Pageable.class))).thenReturn(List.of(r));
        when(posts.findAllById(any())).thenReturn(List.of());
        when(visibility.visiblePostIds(eq(ME), any())).thenReturn(Set.of());

        CollectedPostRow only = query().posts(ME, null, true).items().get(0);
        assertThat(only.available()).isFalse();
        assertThat(only.post()).isNull();
        verify(accounts, never()).findAuthorViewsWithoutTags(any());
    }

    @Test
    void fullPageUsesOneBatchQueryEach() {
        Set<Long> all = new HashSet<>(LongStream.rangeClosed(1, 21).boxed().toList());
        seed(21, all);
        CollectionPage<CollectedPostRow> page = query().posts(ME, null, true);

        assertThat(page.items()).hasSize(20);
        assertThat(page.hasMore()).isTrue();
        verify(posts, times(1)).findAllById(any());
        verify(visibility, times(1)).visiblePostIds(eq(ME), any());
        verify(visibility, never()).isVisibleTo(anyLong(), any());
        verify(accounts, times(1)).findAuthorViewsWithoutTags(any());
        verify(accounts, never()).findAuthorViews(any());
        // 游标由本页最后一条收藏行编码
        KeysetCursor next = KeysetCursor.decodeOrNull(page.nextCursor());
        assertThat(next.id()).isEqualTo(1000 - 20);
        assertThat(next.createdAt()).isEqualTo(T0.minusSeconds(20));
    }

    @Test
    void emptyPageMakesNoBatchCalls() {
        when(contentCollections.findPage(eq(ME), any(), anyLong(), any(Pageable.class))).thenReturn(List.of());
        CollectionPage<CollectedPostRow> page = query().posts(ME, "garbage", true);
        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        verifyNoInteractions(posts, visibility, accounts);
    }

    @Test
    void badCursorIsFirstPage() {
        when(contentCollections.findPage(eq(ME), any(), anyLong(), any(Pageable.class))).thenReturn(List.of());
        query().posts(ME, "%%%not-a-cursor", true);
        verify(contentCollections).findPage(eq(ME), any(), eq(Long.MAX_VALUE), any(Pageable.class));
    }

    @Test
    void excerptCutsAt120CodePointsWithoutSplittingEmoji() {
        String emoji = "🐶";
        String text = "  " + "a".repeat(119) + emoji + "tail  ";
        String ex = MyCollectionQuery.excerpt(text);
        assertThat(ex.codePointCount(0, ex.length())).isEqualTo(120);
        assertThat(ex).endsWith(emoji);
        assertThat(MyCollectionQuery.excerpt("   ")).isNull();
        assertThat(MyCollectionQuery.excerpt(null)).isNull();
        assertThat(MyCollectionQuery.excerpt(" short ")).isEqualTo("short");
    }

    @Test
    void coverEqualsFeedFirstImage() {
        seed(1, Set.of(1L));
        ContentPost p = post(1L, 101L, "text 1", List.of("https://cdn/1.jpg"));
        FeedItemResponse feed = FeedItemResponse.of(p, new AuthorView(101L, "Rina", null, false, List.of()), 0L, false,
                0L, null, null, null, null, com.tailtopia.content.dto.PostMedia.image(p));
        assertThat(query().posts(ME, null, true).items().get(0).post().coverUrl()).isEqualTo(feed.firstImageUrl());
    }

    /** 专题线 video：把第 1 条收藏改成视频帖（封面原地址 cdn/1.jpg）。 */
    private void seedOneVideo() {
        seed(2, Set.of(1L, 2L));
        List<ContentPost> ps = posts.findAllById(List.of());
        ReflectionTestUtils.setField(ps.get(0), "mediaType", com.tailtopia.content.domain.MediaType.VIDEO);
    }

    @Test
    void oldClientSeesVideoPostAsUnavailablePlaceholder() {
        seedOneVideo();
        CollectionPage<CollectedPostRow> page = query().posts(ME, null, false);

        assertThat(page.items()).extracting(CollectedPostRow::postId).containsExactly(1L, 2L);
        assertThat(page.items().get(0).available()).isFalse();
        assertThat(page.items().get(0).post()).isNull();
        assertThat(page.items().get(1).available()).isTrue();
        verifyNoInteractions(postMedia);
    }

    @Test
    void newClientGetsVideoCoverFromMediaResolver() {
        seedOneVideo();
        when(postMedia.resolve(any(), eq(ME))).thenReturn(new com.tailtopia.content.dto.PostMedia(
                com.tailtopia.content.domain.MediaType.VIDEO, null, List.of("https://cdn/signed-cover.jpg")));
        CollectionPage<CollectedPostRow> page = query().posts(ME, null, true);

        assertThat(page.items().get(0).available()).isTrue();
        assertThat(page.items().get(0).post().coverUrl()).isEqualTo("https://cdn/signed-cover.jpg");
        // 图文帖不经解析器，封面仍是首图原样。
        assertThat(page.items().get(1).post().coverUrl()).isEqualTo("https://cdn/2.jpg");
        verify(postMedia, times(1)).resolve(any(), eq(ME));
    }
}
