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
            new HealthRecordService(records, healthEvents, new PetResolver(profiles), events,
                    new PetWriteGuard(profiles));

    @BeforeEach
    void setUp() {
        PetProfile pet = mock(PetProfile.class);
        when(pet.getId()).thenReturn(5L);
        when(profiles.findDefault(7L)).thenReturn(Optional.of(pet));
    }

    private static HealthRecordCreateRequest req(String type, String customName) {
        return new HealthRecordCreateRequest(type, customName, null, LocalDate.of(2024, 1, 1), null);
    }

    @Test
    void createNoProfile404() {
        when(profiles.findDefault(9L)).thenReturn(Optional.empty());
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
        // V1.4.0 multi-pet（Story 1.3 · AC6）：事件带录入记录的这只宠物。
        assertThat(((com.tailtopia.profile.event.HealthRecordCreatedEvent) ev.getValue()).petProfileId())
                .isEqualTo(5L);
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

    // ---------- V1.4.0 multi-pet：按 petId 寻址（AC5） ----------

    @Test
    void petIdAEditingBsRecord404() {
        // A=5（默认，setUp 已备）、B=6；记录 77 属于 B。用 petId=A 改 / 删 → 按 (77, A) 查不到 → 404。
        PetProfile a = mock(PetProfile.class);
        when(a.getId()).thenReturn(5L);
        when(profiles.findByIdAndOwnerId(5L, 7L)).thenReturn(Optional.of(a));
        when(records.findByIdAndPetProfileId(77L, 5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(7L, 5L, 77L,
                new HealthRecordUpdateRequest(null, null, null, null, "x")))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.delete(7L, 5L, 77L)).isInstanceOf(AppException.class);
        verify(records, never()).save(any());
        verify(records, never()).delete(any());
    }

    @Test
    void createWithPetIdBWritesToB() {
        PetProfile b = mock(PetProfile.class);
        when(b.getId()).thenReturn(6L);
        when(profiles.findByIdAndOwnerId(6L, 7L)).thenReturn(Optional.of(b));
        HealthRecord saved = mock(HealthRecord.class);
        when(saved.getId()).thenReturn(1L);
        when(saved.getType()).thenReturn(HealthRecordType.VACCINE);
        when(records.save(any())).thenReturn(saved);
        service.create(7L, 6L, req("VACCINE", null));
        ArgumentCaptor<HealthRecord> cap = ArgumentCaptor.forClass(HealthRecord.class);
        verify(records).save(cap.capture());
        assertThat(cap.getValue().getPetProfileId()).isEqualTo(6L);
    }

    @Test
    void foreignPetId404OnList() {
        when(profiles.findByIdAndOwnerId(99L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.timeline(7L, 99L)).isInstanceOf(AppException.class);
    }

    // ---- V1.4.0 multi-pet Story 2.1（AC7 #4）：只读宠物 → 409 pet-read-only，不写不删、不发事件 ----

    private PetProfile readOnlyPet(long id) {
        PetProfile p = com.tailtopia.profile.support.PetFixtures.pet(7L, id, java.time.Instant.EPOCH);
        p.markReadOnly();
        when(profiles.findByIdAndOwnerId(id, 7L)).thenReturn(Optional.of(p));
        return p;
    }

    private static void assertPetReadOnly(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, long petId) {
        assertThatThrownBy(call).isInstanceOfSatisfying(com.tailtopia.shared.error.PetReadOnlyException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(409);
            assertThat(e.getType()).isEqualTo(com.tailtopia.shared.error.ErrorTypes.PET_READ_ONLY);
            assertThat(e.problemExtensions()).containsEntry("petId", petId);
        });
    }

    @Test
    void createOnReadOnlyPet409() {
        readOnlyPet(8L);
        assertPetReadOnly(() -> service.create(7L, 8L, req("VACCINE", null)), 8L);
        verify(records, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void updateOnReadOnlyPet409() {
        readOnlyPet(8L);
        assertPetReadOnly(() -> service.update(7L, 8L, 1L,
                new HealthRecordUpdateRequest(null, null, null, null, "n")), 8L);
        verify(records, never()).findByIdAndPetProfileId(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(records, never()).save(any());
    }

    @Test
    void deleteOnReadOnlyPet409() {
        readOnlyPet(8L);
        assertPetReadOnly(() -> service.delete(7L, 8L, 1L), 8L);
        verify(records, never()).delete(any());
    }

    /** 归属先于可写：他人宠物仍 404，不泄露其状态。 */
    @Test
    void foreignPetStill404OnWrite() {
        when(profiles.findByIdAndOwnerId(99L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(7L, 99L, req("VACCINE", null)))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }
}
