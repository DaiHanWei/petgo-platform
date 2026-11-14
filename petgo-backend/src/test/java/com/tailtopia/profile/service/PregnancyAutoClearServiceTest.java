package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.event.PetHealthProfileChangedEvent;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.support.PetFixtures;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** L0（V1.5.0 Story 1.3 · AC3）：到期清除 + 事件 changedAt = 当天 00:00 WIB。 */
class PregnancyAutoClearServiceTest {

    private final PetProfileRepository profiles = mock(PetProfileRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PregnancyAutoClearService service = new PregnancyAutoClearService(profiles, events);

    private static PetProfile pregnantSince(long id, String day) {
        PetProfile p = PetFixtures.pet(7L, id, PetType.DOG, Instant.EPOCH);
        p.setSex(PetSex.FEMALE);
        p.setPregnantNursing(true, LocalDate.parse(day).atTime(9, 0).atZone(ScheduleWindow.WIB).toInstant());
        return p;
    }

    @Test
    void clearsOnlyExpiredAndPublishesClearedAtMidnightWib() {
        PetProfile expired = pregnantSince(1L, "2027-01-01");
        PetProfile fresh = pregnantSince(2L, "2027-02-01");
        when(profiles.findByPregnantNursingTrue()).thenReturn(List.of(expired, fresh));
        assertThat(service.clearExpired(LocalDate.parse("2027-05-01"))).isEqualTo(1);
        assertThat(expired.isPregnantNursing()).isFalse();
        assertThat(expired.getPregnantSetAt()).isNull();
        assertThat(fresh.isPregnantNursing()).isTrue();
        verify(events).publishEvent(new PetHealthProfileChangedEvent(7L, 1L, false, false, false, false, true,
                Instant.parse("2027-04-30T17:00:00Z"), true));
    }

    @Test
    void nothingExpiredNoEvents() {
        when(profiles.findByPregnantNursingTrue()).thenReturn(List.of(pregnantSince(1L, "2027-01-01")));
        assertThat(service.clearExpired(LocalDate.parse("2027-04-30"))).isZero();
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void maleStoredFlagAndMissingSetAtClearedWithoutEventForMale() {
        PetProfile male = pregnantSince(1L, "2027-01-01");
        male.setSex(PetSex.MALE);
        PetProfile noSetAt = pregnantSince(2L, "2027-04-01");
        org.springframework.test.util.ReflectionTestUtils.setField(noSetAt, "pregnantSetAt", null);
        when(profiles.findByPregnantNursingTrue()).thenReturn(List.of(male, noSetAt));
        // 返回业务上由「是」变「否」的宠物数：公的只清落库值，不计入、不发事件。
        assertThat(service.clearExpired(LocalDate.parse("2027-05-01"))).isEqualTo(1);
        assertThat(male.isPregnantNursing()).isFalse();
        assertThat(noSetAt.isPregnantNursing()).isFalse();
        verify(events).publishEvent(new PetHealthProfileChangedEvent(7L, 2L, false, false, false, false, true,
                Instant.parse("2027-04-30T17:00:00Z"), true));
        verify(events, org.mockito.Mockito.times(1)).publishEvent(any(Object.class));
    }
}
