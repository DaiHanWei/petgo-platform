package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.consult.event.ConsultClosedEvent;
import com.tailtopia.profile.domain.ArchiveDecision;
import com.tailtopia.profile.domain.HealthEvent;
import com.tailtopia.profile.domain.HealthSourceType;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.HealthEventRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * L0：问诊结束 → 归档 VET_CONSULT 健康事件（Bug 20260701-139）。幂等 + 无档案跳过。
 */
@ExtendWith(MockitoExtension.class)
class ConsultGrowthArchiveListenerTest {

    @Mock
    HealthEventRepository healthEvents;

    @Mock
    PetProfileRepository petProfiles;

    private ConsultGrowthArchiveListener listener() {
        return new ConsultGrowthArchiveListener(healthEvents, petProfiles);
    }

    private static ConsultClosedEvent closed(long sessionId, long userId) {
        return new ConsultClosedEvent(sessionId, userId, 1L, null, "conv", List.of(), true,
                LocalDate.of(2026, 6, 29), "muntah 2x", "YELLOW", "Gastritis ringan");
    }

    @Test
    void archivesVetConsultHealthEventWhenPetExists() {
        PetProfile pet = org.mockito.Mockito.mock(PetProfile.class);
        when(pet.getId()).thenReturn(42L);
        when(healthEvents.existsBySourceRef("consult:7")).thenReturn(false);
        when(petProfiles.findByOwnerId(3L)).thenReturn(Optional.of(pet));

        listener().onConsultClosed(closed(7L, 3L));

        ArgumentCaptor<HealthEvent> cap = ArgumentCaptor.forClass(HealthEvent.class);
        verify(healthEvents).save(cap.capture());
        HealthEvent ev = cap.getValue();
        assertThat(ev.getPetId()).isEqualTo(42L);
        assertThat(ev.getSourceType()).isEqualTo(HealthSourceType.VET_CONSULT);
        assertThat(ev.getSourceRef()).isEqualTo("consult:7");
        assertThat(ev.getEventDate()).isEqualTo(LocalDate.of(2026, 6, 29));
        assertThat(ev.getArchiveDecision()).isEqualTo(ArchiveDecision.ARCHIVED);
    }

    @Test
    void idempotentWhenAlreadyArchived() {
        when(healthEvents.existsBySourceRef("consult:7")).thenReturn(true);

        listener().onConsultClosed(closed(7L, 3L));

        verify(petProfiles, never()).findByOwnerId(org.mockito.ArgumentMatchers.anyLong());
        verify(healthEvents, never()).save(any());
    }

    @Test
    void skipsWhenNoPetProfile() {
        when(healthEvents.existsBySourceRef("consult:7")).thenReturn(false);
        when(petProfiles.findByOwnerId(3L)).thenReturn(Optional.empty());

        listener().onConsultClosed(closed(7L, 3L));

        verify(healthEvents, never()).save(any());
    }
}
