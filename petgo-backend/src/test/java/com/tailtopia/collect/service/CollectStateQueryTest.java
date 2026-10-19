package com.tailtopia.collect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.collect.repository.ContentCollectionRepository;
import com.tailtopia.collect.repository.PlaceCollectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** L0：V1.4.0 ④ Story 2.1 · AC6 —— 详情只读口。 */
@ExtendWith(MockitoExtension.class)
class CollectStateQueryTest {

    @Mock ContentCollectionRepository contents;
    @Mock PlaceCollectionRepository places;

    @Test
    void delegates() {
        CollectStateQuery q = new CollectStateQuery(contents, places);
        when(contents.countByPostIdExcludingUser(55L, 9L)).thenReturn(2L);
        when(contents.existsByUserIdAndPostId(7L, 55L)).thenReturn(true);
        when(places.existsCovering(7L, 20L)).thenReturn(true);

        assertThat(q.collectCountExcludingAuthor(55L, 9L)).isEqualTo(2L);
        assertThat(q.isPostCollected(7L, 55L)).isTrue();
        assertThat(q.isPlaceCollected(7L, 20L)).isTrue();
        verify(places).existsCovering(7L, 20L);
    }
}
