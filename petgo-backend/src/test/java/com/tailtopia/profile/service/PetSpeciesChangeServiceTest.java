package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSpecies;
import com.tailtopia.profile.domain.PetSpeciesChangeSource;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.event.PetSpeciesChangedEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * L0：V1.5.0 pet-species Story 1.2 · AC4 / AC5 —— 保留 / 相同 / 组内换物种 / 冷却 409 / 清品种 / 带品种不清 /
 * 大类变才切清单 / 记修改记录 / 埋点事件。
 */
class PetSpeciesChangeServiceTest {

    /** 2027-02-21 10:00 WIB。 */
    private static final Instant NOW = Instant.parse("2027-02-21T03:00:00Z");
    /** 2026-11-24 19:05 WIB。 */
    private static final Instant LAST_CHANGE = Instant.parse("2026-11-24T12:05:00Z");

    private EntityManager em;
    private MilestoneTrackSwitcher switcher;
    private PetSpeciesChangeLog changeLog;
    private ApplicationEventPublisher events;
    private PetSpeciesChangeService service;

    @BeforeEach
    void setUp() {
        em = mock(EntityManager.class);
        switcher = mock(MilestoneTrackSwitcher.class);
        changeLog = mock(PetSpeciesChangeLog.class);
        events = mock(ApplicationEventPublisher.class);
        service = withClock(NOW);
    }

    private PetSpeciesChangeService withClock(Instant now) {
        return new PetSpeciesChangeService(em, switcher, changeLog, events, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static PetProfile pet(PetSpecies species, String breed) {
        PetProfile p = PetProfile.create(7L, species, "Momo", null, breed, LocalDate.of(2022, 1, 1), null, "T");
        try {
            var f = PetProfile.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, 10L);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    @Test
    void sameSpeciesIsNoop() {
        PetProfile p = pet(PetSpecies.RABBIT, "Lop");
        assertThat(service.applyUserChange(p, PetSpecies.RABBIT, true)).isFalse();
        verifyNoInteractions(em, switcher, changeLog, events);
        assertThat(p.getBreed()).isEqualTo("Lop");
    }

    @Test
    void catToRabbitChangesTypeGroupClearsBreedAndSwitchesTrack() {
        PetProfile p = pet(PetSpecies.CAT, "Persia");
        assertThat(service.applyUserChange(p, PetSpecies.RABBIT, true)).isTrue();

        verify(em).refresh(p, LockModeType.PESSIMISTIC_WRITE);
        assertThat(p.getPetSpecies()).isEqualTo(PetSpecies.RABBIT);
        assertThat(p.getPetType()).isEqualTo(PetType.OTHER);
        assertThat(p.getPetTypeChangedAt()).isEqualTo(NOW);
        assertThat(p.getBreed()).isNull();
        verify(switcher).switchTo(p, PetType.OTHER);
        verify(changeLog).append(10L, PetSpecies.CAT, PetSpecies.RABBIT, PetSpeciesChangeSource.USER, null, null);
        ArgumentCaptor<PetSpeciesChangedEvent> ev = ArgumentCaptor.forClass(PetSpeciesChangedEvent.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue()).isEqualTo(new PetSpeciesChangedEvent(7L, 10L, "CAT", "RABBIT", true, "user"));
    }

    @Test
    void breedProvidedIsKept() {
        PetProfile p = pet(PetSpecies.CAT, "Persia");
        service.applyUserChange(p, PetSpecies.RABBIT, false);
        assertThat(p.getBreed()).isEqualTo("Persia"); // 请求带了品种 → 由调用方随后写入新品种，这里不清
    }

    @Test
    void rabbitToHamsterChangesTypeButNotGroup() {
        PetProfile p = pet(PetSpecies.RABBIT, null);
        service.applyUserChange(p, PetSpecies.HAMSTER, true);
        assertThat(p.getPetTypeChangedAt()).isEqualTo(NOW);
        verify(switcher, never()).switchTo(any(), any());
        verify(changeLog).append(10L, PetSpecies.RABBIT, PetSpecies.HAMSTER, PetSpeciesChangeSource.USER, null, null);
    }

    @Test
    void withinOtherGroupDoesNotTouchCooldownOrBreed() {
        PetProfile p = pet(PetSpecies.OTHER, "Musang pandan");
        p.markTypeChangedAt(LAST_CHANGE); // 冷却中也允许组内换
        service.applyUserChange(p, PetSpecies.CIVET, true);
        assertThat(p.getPetSpecies()).isEqualTo(PetSpecies.CIVET);
        assertThat(p.getPetTypeChangedAt()).isEqualTo(LAST_CHANGE);
        assertThat(p.getBreed()).isEqualTo("Musang pandan");
        verify(switcher, never()).switchTo(any(), any());
        verify(changeLog).append(eq(10L), eq(PetSpecies.OTHER), eq(PetSpecies.CIVET), eq(PetSpeciesChangeSource.USER),
                any(), any());
    }

    @Test
    void cooldownRejectsWith409AndLeavesPetUntouched() {
        PetProfile p = pet(PetSpecies.CAT, "Persia");
        p.markTypeChangedAt(LAST_CHANGE);
        assertThatThrownBy(() -> service.applyUserChange(p, PetSpecies.DOG, true))
                .isInstanceOf(PetSpeciesCooldownException.class)
                .satisfies(e -> assertThat(((PetSpeciesCooldownException) e).getChangeableFrom())
                        .isEqualTo(LocalDate.of(2027, 2, 22)));
        assertThat(p.getPetSpecies()).isEqualTo(PetSpecies.CAT);
        assertThat(p.getBreed()).isEqualTo("Persia");
        verifyNoInteractions(switcher, changeLog, events);
    }

    @Test
    void day91IsAllowed() {
        PetProfile p = pet(PetSpecies.CAT, null);
        p.markTypeChangedAt(LAST_CHANGE);
        withClock(Instant.parse("2027-02-21T17:00:00Z")) // = 2027-02-22 00:00 WIB
                .applyUserChange(p, PetSpecies.DOG, true);
        assertThat(p.getPetSpecies()).isEqualTo(PetSpecies.DOG);
        verify(switcher).switchTo(p, PetType.DOG);
    }

    @Test
    void migratedCatWithOtherTrackToRabbitStillAsksSwitcher() {
        // 迁移过来的猫：pet_type=CAT、track=OTHER。改兔 → groupChanged（CAT→OTHER），切换器见 track 已是 OTHER 自行返回。
        PetProfile p = pet(PetSpecies.OTHER, null);
        p.applySpecies(PetSpecies.CAT);
        service.applyUserChange(p, PetSpecies.RABBIT, true);
        verify(switcher).switchTo(p, PetType.OTHER);
    }
}
