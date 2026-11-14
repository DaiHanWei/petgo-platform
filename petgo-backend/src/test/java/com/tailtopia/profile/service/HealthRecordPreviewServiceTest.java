package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.HealthRecordPreviewRequest;
import com.tailtopia.profile.dto.HealthRecordPreviewResponse;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.support.PetFixtures;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（V1.5.0 Story 1.2 · AC7）：预填接口的组装——范围、锁定、到期疫苗、编辑排除自己、404。 */
class HealthRecordPreviewServiceTest {

    private final HealthRecordRepository records = mock(HealthRecordRepository.class);
    private final PetProfileRepository profiles = mock(PetProfileRepository.class);
    private final HealthRecordPreviewService service = new HealthRecordPreviewService(records,
            new PetResolver(profiles));

    private PetProfile pet(PetType type, PetSex sex, LocalDate birthday) {
        PetProfile p = PetFixtures.pet(7L, 20L, type, Instant.EPOCH);
        p.setBirthday(birthday);
        if (sex != null) {
            p.setSex(sex);
        }
        when(profiles.findByIdAndOwnerId(20L, 7L)).thenReturn(Optional.of(p));
        return p;
    }

    private static HealthRecordPreviewRequest req(Long id, String type, String preset, Integer dose, String deworm,
            String event) {
        return new HealthRecordPreviewRequest(id, type, preset, null, dose, deworm, null,
                event == null ? null : LocalDate.parse(event));
    }

    @Test
    void ac1KittenF4Dose1WithRange() {
        pet(PetType.CAT, PetSex.FEMALE, LocalDate.parse("2026-10-01"));
        HealthRecordPreviewResponse r = service.preview(7L, 20L, req(null, "VACCINE", "F4", 1, null, "2026-12-03"));
        assertThat(r.suggestedNextDate()).isEqualTo(LocalDate.parse("2026-12-31"));
        assertThat(r.nextDateAllowed()).isTrue();
        assertThat(r.nextDateMin()).isEqualTo(LocalDate.parse("2026-12-04"));
        assertThat(r.nextDateMax()).isEqualTo(LocalDate.parse("2029-12-03"));
        assertThat(r.dueVaccinePreset()).isEqualTo("F4");
        assertThat(r.dueDose()).isEqualTo(2);
    }

    @Test
    void ac9DpDose2DueComboDose1() {
        pet(PetType.DOG, PetSex.MALE, LocalDate.parse("2026-12-01"));
        HealthRecordPreviewResponse r = service.preview(7L, 20L, req(null, "VACCINE", "PUPPY_DP", 2, null,
                "2027-03-01"));
        assertThat(r.suggestedNextDate()).isEqualTo(LocalDate.parse("2027-03-22"));
        assertThat(r.dueVaccinePreset()).isEqualTo("COMBO_DHPPI_L");
        assertThat(r.dueDose()).isEqualTo(1);
    }

    @Test
    void noEventDateGivesNullsAndOnlyDateFreeHints() {
        PetProfile p = pet(PetType.CAT, PetSex.FEMALE, null);
        p.setPregnantNursing(true, Instant.now());
        HealthRecordPreviewResponse r = service.preview(7L, 20L, req(null, "VACCINE", "RABIES", null, null, null));
        assertThat(r.suggestedNextDate()).isNull();
        assertThat(r.nextDateMin()).isNull();
        assertThat(r.nextDateMax()).isNull();
        assertThat(r.hints()).containsExactly("VACCINE_PREGNANT", "RABIES_YEARLY");
    }

    @Test
    void neuterNotAllowedAndNoRange() {
        pet(PetType.CAT, PetSex.FEMALE, null);
        HealthRecordPreviewResponse r = service.preview(7L, 20L, req(null, "NEUTER", null, null, null, "2027-01-01"));
        assertThat(r.nextDateAllowed()).isFalse();
        assertThat(r.nextDateMin()).isNull();
    }

    @Test
    void untypedDewormGetsNoLegacyPrefill() {
        pet(PetType.DOG, PetSex.MALE, null);
        assertThat(service.preview(7L, 20L, req(null, "DEWORM", null, null, null, "2027-01-01"))
                .suggestedNextDate()).isNull();
    }

    @Test
    void editingHeatExcludesSelfFromEpisodes() {
        pet(PetType.DOG, PetSex.FEMALE, LocalDate.parse("2024-01-01"));
        HealthRecord first = HealthRecord.create(20L, HealthRecordType.MENSTRUATION, null, null,
                LocalDate.parse("2027-01-01"), null);
        ReflectionTestUtils.setField(first, "id", 1L);
        ReflectionTestUtils.setField(first, "createdAt", Instant.parse("2027-01-01T00:00:00Z"));
        first.setNextDate(LocalDate.parse("2027-07-01"));
        when(records.findByPetProfileIdOrderByEventDateDescIdDesc(20L)).thenReturn(List.of(first));
        // 编辑首条自己：不被自己锁住，按单次发情 +6 个月
        HealthRecordPreviewResponse self = service.preview(7L, 20L, req(1L, "MENSTRUATION", null, null, null,
                "2027-01-01"));
        assertThat(self.nextDateLocked()).isFalse();
        assertThat(self.suggestedNextDate()).isEqualTo(LocalDate.parse("2027-07-01"));
        // 新建一条 1 月 6 日：同周期后续 → 锁定显示首条日期
        HealthRecordPreviewResponse later = service.preview(7L, 20L, req(null, "MENSTRUATION", null, null, null,
                "2027-01-06"));
        assertThat(later.nextDateLocked()).isTrue();
        assertThat(later.lockedNextDate()).isEqualTo(LocalDate.parse("2027-07-01"));
        assertThat(later.suggestedNextDate()).isNull();
        assertThat(later.nextDateMin()).isNull(); // 锁定 → 不给可选范围
        assertThat(later.nextDateMax()).isNull();
    }

    @Test
    void foreignPet404AndBadType422() {
        when(profiles.findByIdAndOwnerId(99L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.preview(7L, 99L, req(null, "VACCINE", null, null, null, null)))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
        pet(PetType.CAT, null, null);
        assertThatThrownBy(() -> service.preview(7L, 20L, req(null, "BLOOD", null, null, null, null)))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getStatus().value()).isEqualTo(422));
    }

    @Test
    void previewValidatesLikeSave() {
        pet(PetType.DOG, PetSex.MALE, null);
        assertThatThrownBy(() -> service.preview(7L, 20L, new HealthRecordPreviewRequest(null, "DEWORM", null, null,
                null, "EXTERNAL", 0, LocalDate.parse("2027-01-01"))))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getStatus().value()).isEqualTo(422));
        assertThatThrownBy(() -> service.preview(7L, 20L, req(null, "VACCINE", "F3", 1, null, "2027-01-01")))
                .isInstanceOf(AppException.class);
        pet(PetType.CAT, PetSex.MALE, null);
        assertThatThrownBy(() -> service.preview(7L, 20L, req(null, "DEWORM", null, null, "HEARTWORM", "2027-01-01")))
                .isInstanceOf(AppException.class);
    }

    @Test
    void editingExternalWithoutDurationUsesStoredDuration() {
        pet(PetType.DOG, PetSex.MALE, null);
        HealthRecord ext = HealthRecord.create(20L, HealthRecordType.DEWORM, null, null,
                LocalDate.parse("2027-01-01"), null);
        ReflectionTestUtils.setField(ext, "id", 5L);
        ext.setDewormType(com.tailtopia.profile.domain.DewormType.EXTERNAL);
        ext.setProductDurationMonths(8);
        when(records.findByPetProfileIdOrderByEventDateDescIdDesc(20L)).thenReturn(List.of(ext));
        assertThat(service.preview(7L, 20L, req(5L, "DEWORM", null, null, "EXTERNAL", "2027-01-01"))
                .suggestedNextDate()).isEqualTo(LocalDate.parse("2027-09-01"));
    }
}
