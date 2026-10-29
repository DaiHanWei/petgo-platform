package com.tailtopia.collect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.collect.dto.CollectedPlaceRow;
import com.tailtopia.collect.dto.CollectionPage;
import com.tailtopia.collect.repository.ContentCollectionRepository;
import com.tailtopia.collect.repository.PlaceCollectionRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.content.service.PostMediaResolver;
import com.tailtopia.place.service.PlaceQueryService;
import com.tailtopia.place.service.PlaceQueryService.CollectedPlaceCard;
import com.tailtopia.shared.paging.KeysetCursor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** L0：V1.4.0 ④ Story 2.2 · AC5 / AC6 / AC9 —— 我的场所收藏列表组装。 */
@ExtendWith(MockitoExtension.class)
class MyCollectionQueryPlacesTest {

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

    @Test
    void visibleAndInvisibleRows() {
        List<Object[]> rows = List.of(
                new Object[] {50L, Timestamp.from(T0), 10L},
                new Object[] {40L, Timestamp.from(T0.minusSeconds(5)), 20L});
        when(placeCollections.findDedupedPage(eq(ME), any(), anyLong(), eq(21))).thenReturn(rows);
        when(placeCards.collectedCards(List.of(10L, 20L))).thenReturn(Map.of(
                10L, new CollectedPlaceCard(10L, "tokA", "Kopi A", PlaceType.CAFE, "Jl. A",
                        "https://cdn/a.jpg?x-oss-process=image/resize,w_320/format,jpg", true),
                20L, new CollectedPlaceCard(20L, "tokB", "Kopi B", PlaceType.CAFE, "Jl. B", null, false)));

        CollectionPage<CollectedPlaceRow> page = query().places(ME, null);

        CollectedPlaceRow a = page.items().get(0);
        assertThat(a.placeToken()).isEqualTo("tokA");
        assertThat(a.available()).isTrue();
        assertThat(a.place().addressText()).isEqualTo("Jl. A");
        assertThat(a.place().coverUrl()).contains("resize,w_320/format,jpg");
        assertThat(a.collectedAt()).isEqualTo(T0);
        CollectedPlaceRow b = page.items().get(1);
        assertThat(b.available()).isFalse();
        assertThat(b.place()).isNull();
        assertThat(b.name()).isEqualTo("Kopi B");
        assertThat(b.placeToken()).isEqualTo("tokB");
        assertThat(page.hasMore()).isFalse();
        verify(placeCards, times(1)).collectedCards(any());
    }

    @Test
    void cursorFromLastDedupedRow() {
        List<Object[]> rows = new ArrayList<>();
        Map<Long, CollectedPlaceCard> cards = new java.util.HashMap<>();
        for (long i = 1; i <= 21; i++) {
            rows.add(new Object[] {100L - i, Timestamp.from(T0.minusSeconds(i)), i});
            cards.put(i, new CollectedPlaceCard(i, "t" + i, "n" + i, PlaceType.CAFE, "a", null, true));
        }
        when(placeCollections.findDedupedPage(eq(ME), any(), anyLong(), anyInt())).thenReturn(rows);
        when(placeCards.collectedCards(any())).thenReturn(cards);

        CollectionPage<CollectedPlaceRow> page = query().places(ME, null);
        assertThat(page.items()).hasSize(20);
        assertThat(page.hasMore()).isTrue();
        KeysetCursor next = KeysetCursor.decodeOrNull(page.nextCursor());
        assertThat(next.id()).isEqualTo(80L);
        assertThat(next.createdAt()).isEqualTo(T0.minusSeconds(20));
    }

    @Test
    void emptyPageShortCircuits() {
        when(placeCollections.findDedupedPage(eq(ME), any(), eq(Long.MAX_VALUE), anyInt())).thenReturn(List.of());
        assertThat(query().places(ME, "bad").items()).isEmpty();
        verifyNoInteractions(placeCards);
    }
}
