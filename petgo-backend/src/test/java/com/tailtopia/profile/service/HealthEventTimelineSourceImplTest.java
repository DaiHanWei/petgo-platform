package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.ArchiveDecision;
import com.tailtopia.profile.repository.HealthEventRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0（V1.4.0 multi-pet）：健康时间线源直接按 petId 查，不再经账号反查唯一宠物。 */
class HealthEventTimelineSourceImplTest {

    private final HealthEventRepository repo = mock(HealthEventRepository.class);
    private final HealthEventTimelineSourceImpl source = new HealthEventTimelineSourceImpl(repo);

    @Test
    void recentQueriesByPetId() {
        LocalDate d = LocalDate.of(2026, 1, 1);
        Instant k = Instant.parse("2026-01-01T00:00:00Z");
        when(repo.findBeforeAnchor(eq(22L), eq(ArchiveDecision.ARCHIVED), eq(d), eq(k), any())).thenReturn(List.of());
        assertThat(source.recentHealthEvents(22L, d, k, 5)).isEmpty();
        verify(repo).findBeforeAnchor(eq(22L), eq(ArchiveDecision.ARCHIVED), eq(d), eq(k), any());
    }

    @Test
    void rangeDayAndCountQueryByPetId() {
        LocalDate d = LocalDate.of(2026, 1, 1);
        when(repo.findByEventDateBetween(22L, ArchiveDecision.ARCHIVED, d, d)).thenReturn(List.of());
        when(repo.countByPetIdAndArchiveDecision(22L, ArchiveDecision.ARCHIVED)).thenReturn(4L);

        assertThat(source.healthEventsOnDay(22L, d)).isEmpty();
        assertThat(source.countHealthEvents(22L)).isEqualTo(4L);
        verify(repo).findByEventDateBetween(22L, ArchiveDecision.ARCHIVED, d, d);
    }
}
