package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.content.repository.ContentPetRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** V1.4.0 multi-pet Story 3.1 · AC2.2 · L0：{@code ContentPetQuery} 单条 / 批量映射。 */
class ContentPetQueryTest {

    private final ContentPetRepository repo = mock(ContentPetRepository.class);
    private final ContentPetQuery query = new ContentPetQuery(repo);

    @Test
    void petsOfReturnsRepositoryOrderOrEmpty() {
        when(repo.findPetIdsOfPost(1L)).thenReturn(List.of(5L, 3L));
        assertThat(query.petsOf(1L)).containsExactly(5L, 3L);
        assertThat(query.petsOf(2L)).isEmpty();
    }

    @Test
    void petsOfAllGroupsInOneQueryKeepingOrder() {
        when(repo.findPetIdsOfPosts(Set.of(1L, 2L, 3L))).thenReturn(List.of(
                new Object[] {1L, 5L}, new Object[] {1L, 3L}, new Object[] {2L, 9L}));

        var out = query.petsOfAll(List.of(1L, 2L, 3L));

        assertThat(out).containsOnlyKeys(1L, 2L); // 没有关联的帖不在 Map 里
        assertThat(out.get(1L)).containsExactly(5L, 3L);
        assertThat(out.get(2L)).containsExactly(9L);
    }

    @Test
    void petsOfAllShortCircuitsOnEmpty() {
        assertThat(query.petsOfAll(List.of())).isEmpty();
        assertThat(query.petsOfAll(null)).isEmpty();
        verifyNoInteractions(repo);
    }
}
