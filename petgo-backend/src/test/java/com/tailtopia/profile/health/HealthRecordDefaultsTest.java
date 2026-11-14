package com.tailtopia.profile.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.VaccinePreset;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0（V1.5.0 Story 1.2 / 1.3）：旧版客户端新建默认值与编辑兼容，PRD AC54。 */
class HealthRecordDefaultsTest {

    private static final PetHealthSnapshot CAT = HealthFixtures.pet(PetType.CAT, PetSex.FEMALE, null, null);
    private static final PetHealthSnapshot DOG = HealthFixtures.pet(PetType.DOG, PetSex.MALE, null, null);

    private static HealthRecord f4(String event, String next) {
        HealthRecord r = HealthRecord.create(1L, HealthRecordType.VACCINE, null, "F4 (Tetracat)",
                LocalDate.parse(event), "note");
        r.setVaccinePreset(VaccinePreset.F4);
        r.setDose(2);
        r.setNextDate(LocalDate.parse(next));
        return r;
    }

    @Test
    void ac54NoteOnlyEditKeepsNextPresetDose() {
        HealthRecord r = f4("2026-10-01", "2026-10-29");
        HealthRecordDefaults.applyForLegacyClient(CAT, r, List.of(),
                new HealthRecordDefaults.LegacyChange(false, false, false));
        assertThat(r.getVaccinePreset()).isEqualTo(VaccinePreset.F4);
        assertThat(r.getDose()).isEqualTo(2);
        assertThat(r.getNextDate()).isEqualTo(LocalDate.parse("2026-10-29"));
    }

    @Test
    void renameRematchesClearsDoseAndRecomputes() {
        HealthRecord r = f4("2026-10-01", "2026-10-29");
        r.setVaccineName("Rabies");
        HealthRecordDefaults.applyForLegacyClient(CAT, r, List.of(),
                new HealthRecordDefaults.LegacyChange(false, true, false));
        assertThat(r.getVaccinePreset()).isEqualTo(VaccinePreset.RABIES);
        assertThat(r.getDose()).isNull();
        assertThat(r.getNextDate()).isEqualTo(LocalDate.parse("2027-10-01"));
    }

    @Test
    void renameToUnmatchedKeepsExistingNextDate() {
        HealthRecord r = f4("2026-10-01", "2026-10-29");
        r.setVaccineName("Vaksin dari klinik");
        HealthRecordDefaults.applyForLegacyClient(CAT, r, List.of(),
                new HealthRecordDefaults.LegacyChange(false, true, false));
        assertThat(r.getVaccinePreset()).isEqualTo(VaccinePreset.OTHER);
        assertThat(r.getNextDate()).isEqualTo(LocalDate.parse("2026-10-29"));
    }

    @Test
    void eventDatePastStoredNextRecomputesOtherwiseKeeps() {
        HealthRecord r = f4("2026-11-05", "2026-10-29");
        HealthRecordDefaults.applyForLegacyClient(CAT, r, List.of(),
                new HealthRecordDefaults.LegacyChange(false, false, true));
        // 成年 / 无生日 F4 第 2 剂 → +1 年
        assertThat(r.getNextDate()).isEqualTo(LocalDate.parse("2027-11-05"));

        HealthRecord ok = f4("2026-10-02", "2026-10-29");
        HealthRecordDefaults.applyForLegacyClient(CAT, ok, List.of(),
                new HealthRecordDefaults.LegacyChange(false, false, true));
        assertThat(ok.getNextDate()).isEqualTo(LocalDate.parse("2026-10-29"));
    }

    @Test
    void ac54LegacyCreateUntypedDewormStoredWithNext() {
        HealthRecord r = HealthRecord.create(1L, HealthRecordType.DEWORM, null, null, LocalDate.parse("2026-12-10"),
                null);
        HealthRecordDefaults.applyForLegacyClient(DOG, r, List.of(), HealthRecordDefaults.LegacyChange.ofCreate());
        assertThat(r.getDewormType()).isNull();
        assertThat(r.getNextDate()).isEqualTo(LocalDate.parse("2027-03-10"));
    }

    @Test
    void customEventDateMovedPastNextClearsIt() {
        HealthRecord r = HealthRecord.create(1L, HealthRecordType.CUSTOM, "Grooming", null,
                LocalDate.parse("2026-11-05"), null);
        r.setNextDate(LocalDate.parse("2026-11-01"));
        HealthRecordDefaults.applyForLegacyClient(CAT, r, List.of(),
                new HealthRecordDefaults.LegacyChange(false, false, true));
        assertThat(r.getNextDate()).isNull();
    }

    @Test
    void renameToUnmatchedPlusEventDatePastNextClearsInvalidNext() {
        HealthRecord r = f4("2027-03-01", "2027-02-01");
        r.setVaccineName("F4 + rabies");
        HealthRecordDefaults.applyForLegacyClient(CAT, r, List.of(),
                new HealthRecordDefaults.LegacyChange(false, true, true));
        assertThat(r.getVaccinePreset()).isEqualTo(VaccinePreset.OTHER);
        assertThat(r.getNextDate()).isNull();
    }

    @Test
    void otherSpeciesEventDatePastNextClearsIt() {
        var other = HealthFixtures.pet(PetType.OTHER, PetSex.FEMALE, null, null);
        HealthRecord r = HealthRecord.create(1L, HealthRecordType.VET_VISIT, null, null, LocalDate.parse("2027-01-10"),
                null);
        r.setNextDate(LocalDate.parse("2026-12-01"));
        HealthRecordDefaults.applyForLegacyClient(other, r, List.of(),
                new HealthRecordDefaults.LegacyChange(false, false, true));
        assertThat(r.getNextDate()).isNull();
    }
}
