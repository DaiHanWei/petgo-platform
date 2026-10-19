package com.tailtopia.collect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.collect.repository.PlaceCollectionRepository;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceStatus;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/** L0：V1.4.0 ④ Story 2.1 · AC5 —— 场所收藏（P-6 合并口径；无事件）。构造器里没有事件发布器即证明无事件。 */
@ExtendWith(MockitoExtension.class)
class PlaceCollectServiceTest {

    @Mock PlaceRepository places;
    @Mock PlaceCollectionRepository collections;

    private static final long ME = 7L;

    private PlaceCollectService service() {
        return new PlaceCollectService(places, collections);
    }

    private static Place place(long id, PlaceStatus status, Long mergedInto, boolean deleted) {
        Place p = mock(Place.class);
        lenient().when(p.getId()).thenReturn(id);
        lenient().when(p.getStatus()).thenReturn(status);
        lenient().when(p.getMergedIntoId()).thenReturn(mergedInto);
        lenient().when(p.getDeletedAt()).thenReturn(deleted ? Instant.now() : null);
        return p;
    }

    @Test
    void activeCollectsItself() {
        Place tokAPlace = place(10L, PlaceStatus.ACTIVE, null, false);
        when(places.resolveForView("tokA")).thenReturn(Optional.of(tokAPlace));
        assertThat(service().collect("tokA", ME)).isTrue();
        verify(collections).insertIfAbsent(ME, 10L);
    }

    @Test
    void mergedTokenLandsOnSurvivor() {
        // resolveForView 已把 MERGED 解析成保留场所 X（id 20）
        Place tokYPlace = place(20L, PlaceStatus.ACTIVE, null, false);
        when(places.resolveForView("tokY")).thenReturn(Optional.of(tokYPlace));
        service().collect("tokY", ME);
        verify(collections).insertIfAbsent(ME, 20L);
    }

    @Test
    void unresolvable404() {
        when(places.resolveForView("gone")).thenReturn(Optional.empty());
        AppException e = catchThrowableOfType(AppException.class, () -> service().collect("gone", ME));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(collections);
    }

    @Test
    void alreadyCoveredSkipsInsert() {
        Place tokXPlace = place(20L, PlaceStatus.ACTIVE, null, false);
        when(places.resolveForView("tokX")).thenReturn(Optional.of(tokXPlace));
        when(collections.existsCovering(ME, 20L)).thenReturn(true);
        assertThat(service().collect("tokX", ME)).isTrue();
        verify(collections, never()).insertIfAbsent(anyLong(), anyLong());
    }

    @Test
    void uncollectTargetsPerStatus() {
        Place actPlace = place(1L, PlaceStatus.ACTIVE, null, false);
        when(places.findAnyByPublicToken("act")).thenReturn(Optional.of(actPlace));
        Place mrgPlace = place(2L, PlaceStatus.MERGED, 20L, false);
        when(places.findAnyByPublicToken("mrg")).thenReturn(Optional.of(mrgPlace));
        Place delPlace = place(3L, PlaceStatus.DELISTED, null, false);
        when(places.findAnyByPublicToken("del")).thenReturn(Optional.of(delPlace));
        Place softPlace = place(4L, PlaceStatus.ACTIVE, null, true);
        when(places.findAnyByPublicToken("soft")).thenReturn(Optional.of(softPlace));
        when(places.findAnyByPublicToken("none")).thenReturn(Optional.empty());

        for (String t : new String[] {"act", "mrg", "del", "soft", "none"}) {
            assertThat(service().uncollect(t, ME)).isFalse();
        }
        verify(collections).deleteForDisplayItem(ME, 1L, 1L);
        verify(collections).deleteForDisplayItem(ME, 2L, 20L);
        verify(collections).deleteForDisplayItem(ME, 3L, 3L);
        verify(collections).deleteForDisplayItem(ME, 4L, 4L);
        org.mockito.Mockito.verifyNoMoreInteractions(collections);
    }

    /** Story 2.2 code-review：已软删的 MERGED 行不跳转（与列表去重同口径），只删它自己那一组。 */
    @Test
    void softDeletedMergedDoesNotJump() {
        Place gone = place(5L, PlaceStatus.MERGED, 20L, true);
        when(places.findAnyByPublicToken("goneMerged")).thenReturn(Optional.of(gone));
        service().uncollect("goneMerged", ME);
        verify(collections).deleteForDisplayItem(ME, 5L, 5L);
    }
}
