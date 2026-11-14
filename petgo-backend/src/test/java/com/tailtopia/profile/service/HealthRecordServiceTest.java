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
                    new PetWriteGuard(profiles), profiles,
                    today -> today);

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
        // V1.5.0 Story 1.1 · AC6：另发一条 HealthRecordChangedEvent（提醒重建），两者互不替代。
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(ev.capture());
        var created = ev.getAllValues().stream()
                .filter(com.tailtopia.profile.event.HealthRecordCreatedEvent.class::isInstance)
                .map(com.tailtopia.profile.event.HealthRecordCreatedEvent.class::cast)
                .toList();
        assertThat(created).hasSize(1);
        // V1.4.0 multi-pet（Story 1.3 · AC6）：事件带录入记录的这只宠物。
        assertThat(created.get(0).petProfileId()).isEqualTo(5L);
        assertThat(ev.getAllValues()).anySatisfy(o -> assertThat(o)
                .isInstanceOf(com.tailtopia.profile.event.HealthRecordChangedEvent.class));
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

    // ================= V1.5.0 健康记录提醒 Story 1.1 · AC4 / AC6：按客户端版本分流 =================

    private static final LocalDate EVENT = LocalDate.of(2026, 10, 1);

    /** 真实宠物（物种 / 性别 / 绝育按需），挂在 petId 上；save 回传入参并补 id。 */
    private PetProfile realPet(long id, com.tailtopia.profile.domain.PetType type) {
        PetProfile p = com.tailtopia.profile.support.PetFixtures.pet(7L, id, type, java.time.Instant.EPOCH);
        when(profiles.findByIdAndOwnerId(id, 7L)).thenReturn(Optional.of(p));
        when(records.save(any())).thenAnswer(inv -> {
            HealthRecord r = inv.getArgument(0);
            if (r.getId() == null) {
                org.springframework.test.util.ReflectionTestUtils.setField(r, "id", 100L);
            }
            return r;
        });
        return p;
    }

    private static HealthRecordCreateRequest v150(String type, String vaccineName, String preset, Integer dose,
            String deworm, Integer months, LocalDate nextDate, Boolean clear) {
        return new HealthRecordCreateRequest(type, null, vaccineName, EVENT, null, preset, dose, deworm, months,
                nextDate, clear);
    }

    private HealthRecord savedRecord() {
        ArgumentCaptor<HealthRecord> cap = ArgumentCaptor.forClass(HealthRecord.class);
        verify(records).save(cap.capture());
        return cap.getValue();
    }

    private HealthRecord existing(long id, long petId, HealthRecordType type) {
        HealthRecord r = HealthRecord.create(petId, type, null, null, EVENT, "old");
        org.springframework.test.util.ReflectionTestUtils.setField(r, "id", id);
        when(records.findByIdAndPetProfileId(id, petId)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void v150CreateWritesNewFieldsAndAppOrigin() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        service.create(7L, 20L, v150("VACCINE", "F4 (Tetracat)", "F4", 2, null, null, EVENT.plusWeeks(4), null), true);
        HealthRecord r = savedRecord();
        assertThat(r.getVaccinePreset()).isEqualTo(com.tailtopia.profile.domain.VaccinePreset.F4);
        assertThat(r.getDose()).isEqualTo(2);
        assertThat(r.getNextDate()).isEqualTo(EVENT.plusWeeks(4));
        assertThat(r.getRecordOrigin()).isEqualTo(com.tailtopia.profile.domain.RecordOrigin.APP);
    }

    @Test
    void v150SingleDosePresetDropsDose() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        service.create(7L, 20L, v150("VACCINE", "Rabies", "RABIES", 3, null, null, null, null), true);
        assertThat(savedRecord().getDose()).isNull();
    }

    @Test
    void v150PresetForWrongSpecies400() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        assertThatThrownBy(() -> service.create(7L, 20L,
                v150("VACCINE", "Puppy DP", "PUPPY_DP", 1, null, null, null, null), true))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getStatus().value()).isEqualTo(422));
        verify(records, never()).save(any());
    }

    @Test
    void v150OtherSpeciesStoresNoPreset() {
        realPet(20L, com.tailtopia.profile.domain.PetType.OTHER);
        service.create(7L, 20L, v150("VACCINE", "Vaksin X", "F4", 1, null, null, null, null), true);
        HealthRecord r = savedRecord();
        assertThat(r.getVaccinePreset()).isNull();
        assertThat(r.getDose()).isNull();
        assertThat(r.getVaccineName()).isEqualTo("Vaksin X");
    }

    @Test
    void v150DewormWithoutType400() {
        realPet(20L, com.tailtopia.profile.domain.PetType.DOG);
        assertThatThrownBy(() -> service.create(7L, 20L, v150("DEWORM", null, null, null, null, null, null, null), true))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getStatus().value()).isEqualTo(422));
    }

    @Test
    void v150HeartwormOnCat400() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        assertThatThrownBy(() -> service.create(7L, 20L,
                v150("DEWORM", null, null, null, "HEARTWORM", null, null, null), true))
                .isInstanceOf(AppException.class);
    }

    @Test
    void v150ExternalDefaultsDurationToOneAndFleaWormKeepsNull() {
        realPet(20L, com.tailtopia.profile.domain.PetType.DOG);
        service.create(7L, 20L, v150("DEWORM", null, null, null, "EXTERNAL", null, null, null), true);
        assertThat(savedRecord().getProductDurationMonths()).isEqualTo(1);

        org.mockito.Mockito.clearInvocations(records);
        service.create(7L, 20L, v150("DEWORM", null, null, null, "FLEA_WORM", 3, null, null), true);
        HealthRecord r = savedRecord();
        assertThat(r.getDewormType()).isEqualTo(com.tailtopia.profile.domain.DewormType.FLEA_WORM);
        assertThat(r.getProductDurationMonths()).isNull();
    }

    @Test
    void v150InvalidDuration400() {
        realPet(20L, com.tailtopia.profile.domain.PetType.DOG);
        assertThatThrownBy(() -> service.create(7L, 20L,
                v150("DEWORM", null, null, null, "EXTERNAL", 2, null, null), true))
                .isInstanceOf(AppException.class);
    }

    @Test
    void v150NextDateRangeEnforced() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        assertThatThrownBy(() -> service.create(7L, 20L,
                v150("CUSTOM", null, null, null, null, null, EVENT, null), true))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.create(7L, 20L,
                v150("VET_VISIT", null, null, null, null, null, EVENT.plusYears(3).plusDays(1), null), true))
                .isInstanceOf(AppException.class);
        service.create(7L, 20L, v150("VET_VISIT", null, null, null, null, null, EVENT.plusYears(3), null), true);
        assertThat(savedRecord().getNextDate()).isEqualTo(EVENT.plusYears(3));
    }

    @Test
    void v150ClearNextDateOnEditAndBackfillBecomesApp() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        HealthRecord r = existing(30L, 20L, HealthRecordType.VET_VISIT);
        r.setNextDate(EVENT.plusMonths(6));
        r.setRecordOrigin(com.tailtopia.profile.domain.RecordOrigin.BACKFILL);
        service.update(7L, 20L, 30L, new HealthRecordUpdateRequest(null, null, null, null, null,
                null, null, null, null, null, true), true);
        assertThat(r.getNextDate()).isNull();
        assertThat(r.getRecordOrigin()).isEqualTo(com.tailtopia.profile.domain.RecordOrigin.APP);
    }

    @Test
    void v150EditWithoutNextDateKeepsItAndOrigin() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        HealthRecord r = existing(30L, 20L, HealthRecordType.CUSTOM);
        r.setCustomName("Grooming");
        r.setNextDate(EVENT.plusMonths(1));
        r.setRecordOrigin(com.tailtopia.profile.domain.RecordOrigin.BACKFILL);
        service.update(7L, 20L, 30L, new HealthRecordUpdateRequest(null, null, null, null, "note"), true);
        assertThat(r.getNextDate()).isEqualTo(EVENT.plusMonths(1));
        assertThat(r.getRecordOrigin()).isEqualTo(com.tailtopia.profile.domain.RecordOrigin.BACKFILL);
    }

    @Test
    void v150EditingLegacyUntypedDewormNoteOnly400() {
        realPet(20L, com.tailtopia.profile.domain.PetType.DOG);
        existing(30L, 20L, HealthRecordType.DEWORM);
        assertThatThrownBy(() -> service.update(7L, 20L, 30L,
                new HealthRecordUpdateRequest(null, null, null, null, "just note"), true))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getStatus().value()).isEqualTo(422));
    }

    @Test
    void legacyCreateIgnoresNewFieldsAndMarksLegacyOrigin() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        service.create(7L, 20L, v150("VACCINE", "Vaksin X", "F3", 2, null, null, EVENT.plusYears(2), null), false);
        HealthRecord r = savedRecord();
        // 请求里的预设 / 剂次 / Next date 一律不读；名字无关键词 → OTHER，无预填（Story 1.2 默认值）。
        assertThat(r.getVaccinePreset()).isEqualTo(com.tailtopia.profile.domain.VaccinePreset.OTHER);
        assertThat(r.getDose()).isNull();
        assertThat(r.getNextDate()).isNull();
        assertThat(r.getRecordOrigin()).isEqualTo(com.tailtopia.profile.domain.RecordOrigin.LEGACY_APP);
    }

    @Test
    void legacyUntypedDewormAllowed() {
        realPet(20L, com.tailtopia.profile.domain.PetType.DOG);
        service.create(7L, 20L, req("DEWORM", null), false);
        HealthRecord r = savedRecord();
        assertThat(r.getDewormType()).isNull();
        // Story 1.2：旧版无类型驱虫 = Legacy，默认 Next date = event + 3 个月（PRD AC54）。
        assertThat(r.getNextDate()).isEqualTo(LocalDate.of(2024, 4, 1));
    }

    @Test
    void legacyEditKeepsNewFields() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        HealthRecord r = existing(30L, 20L, HealthRecordType.VACCINE);
        r.setVaccinePreset(com.tailtopia.profile.domain.VaccinePreset.F4);
        r.setDose(2);
        r.setNextDate(EVENT.plusWeeks(4));
        service.update(7L, 20L, 30L, new HealthRecordUpdateRequest(null, null, null, null, "note",
                "RABIES", 1, null, null, EVENT.plusYears(2), true), false);
        assertThat(r.getVaccinePreset()).isEqualTo(com.tailtopia.profile.domain.VaccinePreset.F4);
        assertThat(r.getDose()).isEqualTo(2);
        assertThat(r.getNextDate()).isEqualTo(EVENT.plusWeeks(4));
    }

    @Test
    void legacyVetVisitRejected() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        assertThatThrownBy(() -> service.create(7L, 20L, req("VET_VISIT", null), false))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getStatus().value()).isEqualTo(422));
        existing(30L, 20L, HealthRecordType.CUSTOM).setCustomName("x");
        assertThatThrownBy(() -> service.update(7L, 20L, 30L,
                new HealthRecordUpdateRequest("VET_VISIT", null, null, null, null), false))
                .isInstanceOf(AppException.class);
        verify(records, never()).save(any());
    }

    @Test
    void legacyListFiltersVetVisitV150KeepsIt() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        HealthRecord visit = HealthRecord.create(20L, HealthRecordType.VET_VISIT, null, null, EVENT, null);
        org.springframework.test.util.ReflectionTestUtils.setField(visit, "id", 1L);
        HealthRecord vac = HealthRecord.create(20L, HealthRecordType.VACCINE, null, "F4", EVENT, null);
        org.springframework.test.util.ReflectionTestUtils.setField(vac, "id", 2L);
        when(records.findByPetProfileIdOrderByEventDateDescIdDesc(20L)).thenReturn(java.util.List.of(visit, vac));
        when(healthEvents.findByPetId(20L)).thenReturn(java.util.List.of());
        assertThat(service.timeline(7L, 20L, false)).extracting(i -> i.type()).containsExactly("VACCINE");
        assertThat(service.timeline(7L, 20L, true)).extracting(i -> i.type())
                .containsExactlyInAnyOrder("VET_VISIT", "VACCINE");
    }

    @Test
    void neuterRecordMarksPetNeuteredAndDeleteDoesNotRevert() {
        PetProfile pet = realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        service.create(7L, 20L, req("NEUTER", null), true);
        assertThat(pet.getNeuterStatus()).isEqualTo(com.tailtopia.profile.domain.NeuterStatus.NEUTERED);
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.atLeastOnce()).publishEvent(ev.capture());
        assertThat(ev.getAllValues()).anySatisfy(o -> {
            assertThat(o).isInstanceOf(com.tailtopia.profile.event.PetHealthProfileChangedEvent.class);
            assertThat(((com.tailtopia.profile.event.PetHealthProfileChangedEvent) o).neuterChanged()).isTrue();
        });

        existing(31L, 20L, HealthRecordType.NEUTER);
        service.delete(7L, 20L, 31L);
        assertThat(pet.getNeuterStatus()).isEqualTo(com.tailtopia.profile.domain.NeuterStatus.NEUTERED);
    }

    @Test
    void editingTypeToNeuterMarksPetNeutered() {
        PetProfile pet = realPet(20L, com.tailtopia.profile.domain.PetType.DOG);
        existing(30L, 20L, HealthRecordType.CUSTOM).setCustomName("x");
        service.update(7L, 20L, 30L, new HealthRecordUpdateRequest("NEUTER", null, null, null, null), false);
        assertThat(pet.getNeuterStatus()).isEqualTo(com.tailtopia.profile.domain.NeuterStatus.NEUTERED);
    }

    @Test
    void noteOnlyEditIsNotReminderRelevantOtherFieldIs() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        existing(30L, 20L, HealthRecordType.VET_VISIT);
        service.update(7L, 20L, 30L, new HealthRecordUpdateRequest(null, null, null, null, "new note"), true);
        service.update(7L, 20L, 30L, new HealthRecordUpdateRequest(null, null, null, EVENT.minusDays(1), null), true);
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(ev.capture());
        var changes = ev.getAllValues().stream()
                .map(com.tailtopia.profile.event.HealthRecordChangedEvent.class::cast).toList();
        assertThat(changes.get(0).reminderRelevant()).isFalse();
        assertThat(changes.get(1).reminderRelevant()).isTrue();
        assertThat(changes.get(1).change())
                .isEqualTo(com.tailtopia.profile.event.HealthRecordChangedEvent.Change.UPDATED);
    }

    @Test
    void deletePublishesDeletedChange() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        existing(30L, 20L, HealthRecordType.CUSTOM);
        service.delete(7L, 20L, 30L);
        verify(events).publishEvent(new com.tailtopia.profile.event.HealthRecordChangedEvent(7L, 20L, 30L,
                com.tailtopia.profile.event.HealthRecordChangedEvent.Change.DELETED, true));
    }

    // ---- 复审修正（Story 1.1 code review） ----

    @Test
    void v150EditMovingEventDatePastStoredNextDate422() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        HealthRecord r = existing(30L, 20L, HealthRecordType.VET_VISIT);
        r.setNextDate(EVENT.plusDays(28));
        assertThatThrownBy(() -> service.update(7L, 20L, 30L,
                new HealthRecordUpdateRequest(null, null, null, EVENT.plusDays(35), null), true))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getStatus().value()).isEqualTo(422));
    }

    @Test
    void legacyTypeChangeDropsOtherTypesFields() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        HealthRecord r = existing(30L, 20L, HealthRecordType.VACCINE);
        r.setVaccinePreset(com.tailtopia.profile.domain.VaccinePreset.F4);
        r.setDose(2);
        r.setNextDate(EVENT.plusWeeks(4));
        service.update(7L, 20L, 30L, new HealthRecordUpdateRequest("DEWORM", null, null, null, null), false);
        assertThat(r.getVaccinePreset()).isNull();
        assertThat(r.getDose()).isNull();
        assertThat(r.getNextDate()).isEqualTo(EVENT.plusWeeks(4)); // Next date 不是类型专属字段，保留
    }

    @Test
    void v150PresetChangeReplacesOldLabelButKeepsCustomName() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        HealthRecord r = existing(30L, 20L, HealthRecordType.VACCINE);
        r.setVaccinePreset(com.tailtopia.profile.domain.VaccinePreset.F4);
        r.setVaccineName("F4 (Tetracat)");
        service.update(7L, 20L, 30L, new HealthRecordUpdateRequest(null, null, null, null, null,
                "RABIES", null, null, null, null, null), true);
        assertThat(r.getVaccineName()).isEqualTo("Rabies");

        r.setVaccineName("Vaksin dari klinik");
        service.update(7L, 20L, 30L, new HealthRecordUpdateRequest(null, null, null, null, null,
                "F3", 1, null, null, null, null), true);
        assertThat(r.getVaccineName()).isEqualTo("Vaksin dari klinik");
        assertThat(r.getVaccinePreset()).isEqualTo(com.tailtopia.profile.domain.VaccinePreset.F3);
    }

    // ---- Story 1.2 · AC8：旧版新建写默认值；列表带显示字段 ----

    @Test
    void legacyCreateKeywordMatchesAndPrefills() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        service.create(7L, 20L, new HealthRecordCreateRequest("VACCINE", null, "Vaksin tricat",
                LocalDate.of(2026, 2, 1), null), false);
        HealthRecord r = savedRecord();
        assertThat(r.getVaccinePreset()).isEqualTo(com.tailtopia.profile.domain.VaccinePreset.F3);
        assertThat(r.getVaccineName()).isEqualTo("Vaksin tricat"); // 原文不动
        assertThat(r.getNextDate()).isEqualTo(LocalDate.of(2027, 2, 1)); // 成年 / 无生日 空剂次 → +1 年（AC50）
    }

    @Test
    void timelineCarriesDisplayStatus() {
        realPet(20L, com.tailtopia.profile.domain.PetType.CAT);
        HealthRecord vac = HealthRecord.create(20L, HealthRecordType.VACCINE, null, "F4", LocalDate.of(2020, 1, 1),
                null);
        org.springframework.test.util.ReflectionTestUtils.setField(vac, "id", 2L);
        vac.setVaccinePreset(com.tailtopia.profile.domain.VaccinePreset.F4);
        vac.setNextDate(LocalDate.of(2020, 2, 1));
        when(records.findByPetProfileIdOrderByEventDateDescIdDesc(20L)).thenReturn(java.util.List.of(vac));
        when(healthEvents.findByPetId(20L)).thenReturn(java.util.List.of());
        var item = service.timeline(7L, 20L, true).get(0);
        assertThat(item.recordStatus()).isEqualTo("OVERDUE");
        assertThat(item.displayNextDate()).isEqualTo(LocalDate.of(2020, 2, 1));
    }
}
