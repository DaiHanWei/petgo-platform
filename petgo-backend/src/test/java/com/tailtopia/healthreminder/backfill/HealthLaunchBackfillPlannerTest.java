package com.tailtopia.healthreminder.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.NeuterStatus;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.RecordOrigin;
import com.tailtopia.profile.domain.VaccinePreset;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.PetHealthSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** L0（V1.5.0 Story 1.3 · AC1）：上线回填的字段决策，PRD AC50–AC53。 */
class HealthLaunchBackfillPlannerTest {

    private static final AtomicLong IDS = new AtomicLong();

    private static PetHealthSnapshot pet(PetType type, PetSex sex, String birthday, NeuterStatus neuter) {
        return new PetHealthSnapshot(1L, 7L, type, sex, birthday == null ? null : LocalDate.parse(birthday), neuter,
                null, false, LocalDate.parse("2025-01-01"), PetStatus.ACTIVE);
    }

    private static HealthRecordSnapshot rec(HealthRecordType type, String name, DewormType deworm, String event,
            RecordOrigin origin) {
        long id = IDS.incrementAndGet();
        return new HealthRecordSnapshot(id, type, null, name, null, deworm, null, LocalDate.parse(event), null, null,
                origin, Instant.ofEpochSecond(id));
    }

    private static HealthRecordSnapshot backfill(HealthRecordType type, String name, String event) {
        return rec(type, name, null, event, RecordOrigin.BACKFILL);
    }

    private static BackfillPlanner.RecordUpdate only(BackfillPlanner.Plan plan) {
        assertThat(plan.records()).hasSize(1);
        return plan.records().get(0);
    }

    @Test
    void ac50AdultCatTricatBecomesF3NextOneYear() {
        var u = only(BackfillPlanner.plan(pet(PetType.CAT, PetSex.FEMALE, "2022-01-01", null),
                List.of(backfill(HealthRecordType.VACCINE, "Vaksin tricat", "2026-02-01"))));
        assertThat(u.setPreset()).isEqualTo(VaccinePreset.F3);
        assertThat(u.setNextDate()).isEqualTo(LocalDate.parse("2027-02-01"));
    }

    @Test
    void ac51TwoPresetsIsOtherWithoutNext() {
        var u = only(BackfillPlanner.plan(pet(PetType.CAT, PetSex.FEMALE, null, null),
                List.of(backfill(HealthRecordType.VACCINE, "F4 + rabies", "2026-02-01"))));
        assertThat(u.setPreset()).isEqualTo(VaccinePreset.OTHER);
        assertThat(u.setNextDate()).isNull();
    }

    @Test
    void ac51DogComboAndLepto() {
        var dog = pet(PetType.DOG, PetSex.MALE, null, null);
        assertThat(only(BackfillPlanner.plan(dog, List.of(backfill(HealthRecordType.VACCINE, "Vaksin DHPPi+L",
                "2026-02-01")))).setPreset()).isEqualTo(VaccinePreset.COMBO_DHPPI_L);
        assertThat(only(BackfillPlanner.plan(dog, List.of(backfill(HealthRecordType.VACCINE, "dhpp + lepto",
                "2026-02-01")))).setPreset()).isEqualTo(VaccinePreset.COMBO_DHPPI_L);
        assertThat(only(BackfillPlanner.plan(dog, List.of(backfill(HealthRecordType.VACCINE, "Lepto",
                "2026-02-01")))).setPreset()).isEqualTo(VaccinePreset.LEPTO);
    }

    @Test
    void kittenBlankDoseUnder16WeeksPlusFourWeeks() {
        var u = only(BackfillPlanner.plan(pet(PetType.CAT, PetSex.FEMALE, "2026-01-01", null),
                List.of(backfill(HealthRecordType.VACCINE, "Tricat", "2026-03-01"))));
        assertThat(u.setNextDate()).isEqualTo(LocalDate.parse("2026-03-29"));
    }

    @Test
    void ac53LegacyDewormPlusThreeMonthsTypeStaysNull() {
        var u = only(BackfillPlanner.plan(pet(PetType.DOG, PetSex.MALE, null, null),
                List.of(backfill(HealthRecordType.DEWORM, null, "2026-10-15"))));
        assertThat(u.setPreset()).isNull();
        assertThat(u.setNextDate()).isEqualTo(LocalDate.parse("2027-01-15"));
    }

    @Test
    void neuterRecordMarksNeuteredAndBlocksHeatNext() {
        var plan = BackfillPlanner.plan(pet(PetType.DOG, PetSex.FEMALE, null, null), List.of(
                backfill(HealthRecordType.NEUTER, null, "2025-05-01"),
                backfill(HealthRecordType.MENSTRUATION, null, "2025-01-01")));
        assertThat(plan.markNeutered()).isTrue();
        assertThat(plan.records()).isEmpty();
    }

    @Test
    void intactFemaleDogHeatEpisodeFirstGetsNextLaterRecordNot() {
        var first = backfill(HealthRecordType.MENSTRUATION, null, "2026-01-01");
        var later = backfill(HealthRecordType.MENSTRUATION, null, "2026-01-05");
        var plan = BackfillPlanner.plan(pet(PetType.DOG, PetSex.FEMALE, null, null), List.of(first, later));
        assertThat(plan.records()).singleElement().satisfies(u -> {
            assertThat(u.recordId()).isEqualTo(first.id());
            assertThat(u.setNextDate()).isEqualTo(LocalDate.parse("2026-07-01"));
        });
        // 猫 / 公的发情不写
        assertThat(BackfillPlanner.plan(pet(PetType.CAT, PetSex.FEMALE, null, null), List.of(first)).isEmpty())
                .isTrue();
    }

    @Test
    void otherSpeciesCustomNeuterAndAppRecordsUntouched() {
        assertThat(BackfillPlanner.plan(pet(PetType.OTHER, PetSex.FEMALE, null, null),
                List.of(backfill(HealthRecordType.VACCINE, "Rabies", "2026-01-01"))).isEmpty()).isTrue();
        assertThat(BackfillPlanner.plan(pet(PetType.CAT, PetSex.FEMALE, null, null), List.of(
                backfill(HealthRecordType.CUSTOM, null, "2026-01-01"),
                rec(HealthRecordType.VACCINE, "Tricat", null, "2026-01-01", RecordOrigin.APP))).isEmpty()).isTrue();
    }

    @Test
    void idempotentOnceFieldsFilled() {
        var filled = new HealthRecordSnapshot(99L, HealthRecordType.VACCINE, VaccinePreset.F3, "Tricat", null, null,
                null, LocalDate.parse("2026-02-01"), LocalDate.parse("2027-02-01"), null, RecordOrigin.BACKFILL,
                Instant.EPOCH);
        assertThat(BackfillPlanner.plan(pet(PetType.CAT, PetSex.FEMALE, null, NeuterStatus.NEUTERED),
                List.of(filled)).isEmpty()).isTrue();
    }

    @Test
    void onlyBackfillNeuterRecordsMarkNeutered() {
        var appNeuter = rec(HealthRecordType.NEUTER, null, null, "2026-12-01", RecordOrigin.APP);
        assertThat(BackfillPlanner.plan(pet(PetType.DOG, PetSex.MALE, null, NeuterStatus.UNKNOWN),
                List.of(appNeuter, backfill(HealthRecordType.CUSTOM, null, "2026-01-01"))).markNeutered()).isFalse();
    }
}
