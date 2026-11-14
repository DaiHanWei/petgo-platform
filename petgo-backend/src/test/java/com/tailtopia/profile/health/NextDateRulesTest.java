package com.tailtopia.profile.health;

import static com.tailtopia.profile.health.HealthFixtures.d;
import static com.tailtopia.profile.health.HealthFixtures.draft;
import static com.tailtopia.profile.health.HealthFixtures.pet;
import static com.tailtopia.profile.health.HealthFixtures.pregnant;
import static com.tailtopia.profile.health.HealthFixtures.rec;
import static com.tailtopia.profile.health.HealthFixtures.vaccine;
import static com.tailtopia.profile.health.HealthFixtures.withCat;
import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.NeuterStatus;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.VaccinePreset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0（V1.5.0 Story 1.2 · AC7 规则部分）：NextDateRules 门面 + PreviewHints。 */
class NextDateRulesTest {

    private static final PetHealthSnapshot DOG_F = pet(PetType.DOG, PetSex.FEMALE, d("2025-01-01"), null);

    @Test
    void neuterAndNonFemaleDogHeatNotAllowed() {
        assertThat(NextDateRules.plan(DOG_F, draft(HealthRecordType.NEUTER, null, null, null, "2027-01-01"),
                List.of()).allowed()).isFalse();
        var cat = pet(PetType.CAT, PetSex.FEMALE, null, null);
        assertThat(NextDateRules.plan(cat, draft(HealthRecordType.MENSTRUATION, null, null, null, "2027-01-01"),
                List.of()).allowed()).isFalse();
        var male = pet(PetType.DOG, PetSex.MALE, null, null);
        assertThat(NextDateRules.plan(male, draft(HealthRecordType.MENSTRUATION, null, null, null, "2027-01-01"),
                List.of()).allowed()).isFalse();
        var noSex = pet(PetType.DOG, null, null, null);
        assertThat(NextDateRules.plan(noSex, draft(HealthRecordType.MENSTRUATION, null, null, null, "2027-01-01"),
                List.of()).allowed()).isFalse();
    }

    @Test
    void otherSpeciesAllowedButNeverPrefilled() {
        var other = pet(PetType.OTHER, PetSex.FEMALE, d("2025-01-01"), null);
        var plan = NextDateRules.plan(other, draft(HealthRecordType.DEWORM, null, null, DewormType.INTERNAL,
                "2027-01-01"), List.of());
        assertThat(plan.allowed()).isTrue();
        assertThat(plan.suggestion()).isNull();
    }

    @Test
    void ac31HeatFirstPrefilledLaterLocked() {
        var first = rec(HealthRecordType.MENSTRUATION, "2027-01-01", "2027-07-01");
        var plan = NextDateRules.plan(DOG_F, draft(HealthRecordType.MENSTRUATION, null, null, null, "2027-07-01"),
                List.of(first));
        assertThat(plan.suggestion().nextDate()).isEqualTo(d("2027-12-29"));
        var later = NextDateRules.plan(DOG_F, draft(HealthRecordType.MENSTRUATION, null, null, null, "2027-01-06"),
                List.of(first));
        assertThat(later.locked()).isTrue();
        assertThat(later.lockedNextDate()).isEqualTo(d("2027-07-01"));
    }

    @Test
    void comboUsesDpHistory() {
        var dp = vaccine(VaccinePreset.PUPPY_DP, "Puppy DP", "2025-03-01", null);
        var plan = NextDateRules.plan(DOG_F, draft(HealthRecordType.VACCINE, VaccinePreset.COMBO_DHPPI_L, 2, null,
                "2025-04-01"), List.of(dp));
        assertThat(plan.suggestion().nextDate()).isEqualTo(d("2026-04-01")); // 有 DP → 第 2 剂完成 → +1 年
    }

    @Test
    void hintsInFixedOrder() {
        var p = withCat(pregnant(pet(PetType.DOG, PetSex.FEMALE, d("2027-01-01"), null)));
        var hints = PreviewHints.of(p, draft(HealthRecordType.DEWORM, null, null, DewormType.FLEA_WORM,
                "2027-02-01"), List.of());
        assertThat(hints).containsExactly(PreviewHint.DEWORM_UNDER_8_WEEKS, PreviewHint.DEWORM_PREGNANT,
                PreviewHint.PERMETHRIN);
        var vac = PreviewHints.of(p, draft(HealthRecordType.VACCINE, VaccinePreset.RABIES, null, null, null),
                List.of());
        assertThat(vac).containsExactly(PreviewHint.VACCINE_PREGNANT, PreviewHint.RABIES_YEARLY);
    }

    @Test
    void heartwormTestOnlyOver6MonthsWithoutHistoryAndHeatNeutered() {
        var dog = pet(PetType.DOG, PetSex.MALE, d("2025-01-01"), NeuterStatus.NEUTERED);
        var hw = draft(HealthRecordType.DEWORM, null, null, DewormType.HEARTWORM, "2027-01-01");
        assertThat(PreviewHints.of(dog, hw, List.of())).containsExactly(PreviewHint.HEARTWORM_TEST);
        var prior = HealthFixtures.deworm(DewormType.HEARTWORM, "2026-12-01", null);
        assertThat(PreviewHints.of(dog, hw, List.of(prior))).isEmpty();
        assertThat(PreviewHints.of(dog, draft(HealthRecordType.MENSTRUATION, null, null, null, null), List.of()))
                .containsExactly(PreviewHint.HEAT_NEUTERED);
    }
}
