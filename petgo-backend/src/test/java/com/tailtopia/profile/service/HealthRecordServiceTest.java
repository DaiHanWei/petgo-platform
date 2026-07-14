package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.HealthRecordCreateRequest;
import com.tailtopia.profile.dto.HealthRecordUpdateRequest;
import com.tailtopia.profile.repository.HealthEventRepository;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import org.springframework.context.ApplicationEventPublisher;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** L0：健康记录 CRUD 校验/归属分支（Story 7.1 · AC2）——无档案 404 / 非法类型 422 / CUSTOM 缺名 422 / 越权 404。 */
class HealthRecordServiceTest {

    private final HealthRecordRepository records = mock(HealthRecordRepository.class);
    private final HealthEventRepository healthEvents = mock(HealthEventRepository.class);
    private final PetProfileRepository profiles = mock(PetProfileRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final HealthRecordService service =
            new HealthRecordService(records, healthEvents, profiles, events);

    @BeforeEach
    void setUp() {
        PetProfile pet = mock(PetProfile.class);
        when(pet.getId()).thenReturn(5L);
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(pet));
    }

    private static HealthRecordCreateRequest req(String type, String customName) {
        return new HealthRecordCreateRequest(type, customName, null, LocalDate.of(2024, 1, 1), null);
    }

    @Test
    void createNoProfile404() {
        when(profiles.findByOwnerId(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(9L, req("VACCINE", null)))
                .isInstanceOf(AppException.class);
        verify(records, never()).save(any());
    }

    @Test
    void createInvalidType422() {
        assertThatThrownBy(() -> service.create(7L, req("BLOODTEST", null)))
                .isInstanceOf(AppException.class);
        verify(records, never()).save(any());
    }

    @Test
    void createCustomWithoutName422() {
        assertThatThrownBy(() -> service.create(7L, req("CUSTOM", "  ")))
                .isInstanceOf(AppException.class);
        verify(records, never()).save(any());
    }

    @Test
    void createValidSavesWithPetIdAndType() {
        HealthRecord saved = mock(HealthRecord.class);
        when(saved.getId()).thenReturn(1L);
        when(saved.getType()).thenReturn(HealthRecordType.VACCINE);
        when(saved.getEventDate()).thenReturn(LocalDate.of(2024, 1, 1));
        when(records.save(any())).thenReturn(saved);

        service.create(7L, new HealthRecordCreateRequest("VACCINE", null, "Rabies", LocalDate.of(2024, 1, 1), "ok"));

        ArgumentCaptor<HealthRecord> cap = ArgumentCaptor.forClass(HealthRecord.class);
        verify(records).save(cap.capture());
        assertThat(cap.getValue().getPetProfileId()).isEqualTo(5L);
        assertThat(cap.getValue().getType()).isEqualTo(HealthRecordType.VACCINE);
        assertThat(cap.getValue().getVaccineName()).isEqualTo("Rabies");

        // 里程碑第四路径（AC2）：create 发 HealthRecordCreatedEvent 供 listener 完成 M3。
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue()).isInstanceOf(
                com.tailtopia.profile.event.HealthRecordCreatedEvent.class);
    }

    @Test
    void updateNonOwnedRecord404() {
        when(records.findByIdAndPetProfileId(99L, 5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(7L, 99L,
                new HealthRecordUpdateRequest(null, null, null, null, "x")))
                .isInstanceOf(AppException.class);
    }

    @Test
    void deleteNonOwnedRecord404() {
        when(records.findByIdAndPetProfileId(99L, 5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(7L, 99L)).isInstanceOf(AppException.class);
        verify(records, never()).delete(any());
    }
}
