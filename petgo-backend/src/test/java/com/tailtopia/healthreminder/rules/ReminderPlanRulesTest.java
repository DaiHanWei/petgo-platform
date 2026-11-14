package com.tailtopia.healthreminder.rules;

import static com.tailtopia.healthreminder.rules.RuleFixtures.backfill;
import static com.tailtopia.healthreminder.rules.RuleFixtures.d;
import static com.tailtopia.healthreminder.rules.RuleFixtures.deworm;
import static com.tailtopia.healthreminder.rules.RuleFixtures.of;
import static com.tailtopia.healthreminder.rules.RuleFixtures.pet;
import static com.tailtopia.healthreminder.rules.RuleFixtures.vaccine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.PushPoint;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.domain.ReminderStatus;
import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.NeuterStatus;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.VaccinePreset;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.PetHealthSnapshot;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0（V1.5.0 Story 2.1 · AC3）：记录类 / 事件类期望集合，用例名写 PRD AC 编号。 */
class ReminderPlanRulesTest {

    private static final PetHealthSnapshot CAT = pet(PetType.CAT, PetSex.FEMALE, "2022-01-01", null);
    private static final PetHealthSnapshot DOG_F = pet(PetType.DOG, PetSex.FEMALE, "2022-01-01", null);
    private static final PetHealthSnapshot DOG_M = pet(PetType.DOG, PetSex.MALE, "2022-01-01", null);
    private static final LocalDate LAUNCH = d("2026-01-01");

    private static List<DesiredReminder> plan(PetHealthSnapshot pet, List<HealthRecordSnapshot> recs, String from) {
        return plan(pet, recs, ReminderHistory.EMPTY, from);
    }

    private static List<DesiredReminder> plan(PetHealthSnapshot pet, List<HealthRecordSnapshot> recs,
            ReminderHistory history, String from) {
        return ReminderPlanRules.recordAndEventReminders(pet, recs, history, d(from), LAUNCH);
    }

    @Test
    void ac18VaccinePointsAndNewerRecordReplaces() {
        var f4 = vaccine(VaccinePreset.F4, 2, "2026-11-03", "2026-12-01");
        assertThat(plan(CAT, List.of(f4), "2026-11-10")).extracting(DesiredReminder::kind, DesiredReminder::dueDate)
                .containsExactly(tuple(ReminderKind.VACCINE_D_MINUS_7, d("2026-11-24")),
                        tuple(ReminderKind.VACCINE_D_DAY, d("2026-12-01")),
                        tuple(ReminderKind.VACCINE_D_PLUS_7, d("2026-12-08")));
        var newer = vaccine(VaccinePreset.F4, 3, "2026-11-26", "2027-11-26");
        assertThat(plan(CAT, List.of(f4, newer), "2026-11-27")).extracting(DesiredReminder::sourceRecordId)
                .containsOnly(newer.id());
    }

    @Test
    void ac18DueVaccineSnapshotAndAc9DpDueCombo() {
        var f4 = vaccine(VaccinePreset.F4, 1, "2026-03-01", "2026-03-29");
        var d0 = plan(CAT, List.of(f4), "2026-03-01").get(0);
        assertThat(d0.dueVaccinePreset()).isEqualTo(VaccinePreset.F4);
        assertThat(d0.dueDose()).isEqualTo(2);
        assertThat(d0.dueVaccineName()).isEqualTo("F4 (Tetracat)");
        var dp = vaccine(VaccinePreset.PUPPY_DP, 2, "2027-03-01", "2027-03-22");
        var puppy = pet(PetType.DOG, PetSex.MALE, "2026-12-01", null);
        var dpPlan = plan(puppy, List.of(dp), "2027-03-02");
        assertThat(dpPlan.get(0).dueVaccinePreset()).isEqualTo(VaccinePreset.COMBO_DHPPI_L);
        assertThat(dpPlan.get(0).dueDose()).isEqualTo(1);
        assertThat(dpPlan.get(0).dueDate()).isEqualTo(d("2027-03-15"));
        // AC9：20 Mar 记 Combo → DP 被覆盖，DP 的点撤掉
        var combo = vaccine(VaccinePreset.COMBO_DHPPI_L, 1, "2027-03-20", "2027-04-17");
        assertThat(plan(puppy, List.of(dp, combo), "2027-03-21")).extracting(DesiredReminder::sourceRecordId)
                .containsOnly(combo.id());
    }

    @Test
    void ac8F3StopsF4() {
        var f4 = vaccine(VaccinePreset.F4, 1, "2026-03-01", "2026-03-29");
        var f3 = vaccine(VaccinePreset.F3, 2, "2026-03-20", "2027-03-20");
        assertThat(plan(CAT, List.of(f4, f3), "2026-03-21")).extracting(DesiredReminder::sourceRecordId)
                .containsOnly(f3.id());
    }

    @Test
    void ac14VetVisitDMinus1AndDDaySatisfiedOnlyFromDMinus1() {
        var visit = of(HealthRecordType.VET_VISIT, "2026-10-01", "2026-11-14");
        assertThat(plan(CAT, List.of(visit), "2026-10-02")).extracting(DesiredReminder::kind, DesiredReminder::dueDate)
                .containsExactly(tuple(ReminderKind.VET_VISIT_D_MINUS_1, d("2026-11-13")),
                        tuple(ReminderKind.VET_VISIT_D_DAY, d("2026-11-14")));
        var early = of(HealthRecordType.VET_VISIT, "2026-11-05", null);
        assertThat(plan(CAT, List.of(visit, early), "2026-11-06")).hasSize(2);
        var onD1 = of(HealthRecordType.VET_VISIT, "2026-11-13", null);
        assertThat(plan(CAT, List.of(visit, early, onD1), "2026-11-13")).isEmpty();
    }

    @Test
    void ac20DeletingLatestBringsBackPreviousFuturePoints() {
        var old = deworm(DewormType.INTERNAL, "2026-10-01", "2027-01-01");
        var newer = deworm(DewormType.INTERNAL, "2026-11-01", "2027-02-01");
        assertThat(plan(DOG_M, List.of(old, newer), "2026-11-02")).extracting(DesiredReminder::sourceRecordId)
                .containsOnly(newer.id());
        assertThat(plan(DOG_M, List.of(old), "2026-11-02")).extracting(DesiredReminder::kind, DesiredReminder::dueDate)
                .containsExactly(tuple(ReminderKind.DEWORM_INTERNAL_D_DAY, d("2027-01-01")),
                        tuple(ReminderKind.DEWORM_INTERNAL_D_PLUS_3, d("2027-01-04")));
    }

    @Test
    void ac21CoverageIsOneWayAndLasts() {
        var internal = deworm(DewormType.INTERNAL, "2027-01-01", "2027-04-01");
        var fw = deworm(DewormType.FLEA_WORM, "2027-02-01", "2027-03-01");
        var withFw = plan(DOG_M, List.of(internal, fw), "2027-02-02");
        assertThat(withFw).extracting(DesiredReminder::sourceRecordId).containsOnly(fw.id());
        assertThat(withFw).extracting(DesiredReminder::kind).containsExactly(ReminderKind.DEWORM_FLEA_WORM_D_DAY,
                ReminderKind.DEWORM_EXTERNAL_D_PLUS_3);
        // 删 Flea+worm → 1 Apr 回来
        assertThat(plan(DOG_M, List.of(internal), "2027-02-02")).extracting(DesiredReminder::dueDate)
                .contains(d("2027-04-01"));
        // 15 Feb 新 Internal：新周期 15 May，Flea+worm 的 1 Mar 不变
        var newer = deworm(DewormType.INTERNAL, "2027-02-15", "2027-05-15");
        var after = plan(DOG_M, List.of(internal, fw, newer), "2027-02-16");
        assertThat(after).extracting(DesiredReminder::sourceRecordId, DesiredReminder::dueDate).contains(
                tuple(fw.id(), d("2027-03-01")), tuple(newer.id(), d("2027-05-15")));
        assertThat(after).extracting(DesiredReminder::sourceRecordId).doesNotContain(internal.id());
    }

    @Test
    void ac22BackDatedPastPointsNotGenerated() {
        var rabies = vaccine(VaccinePreset.RABIES, null, "2026-02-01", "2027-02-01");
        assertThat(plan(DOG_M, List.of(rabies), "2027-03-11")).isEmpty();
    }

    @Test
    void ac53LegacyDewormAndTypedCancels() {
        var legacy = backfill(deworm(null, "2026-10-15", "2027-01-15"));
        var launch = d("2026-12-01");
        var p = ReminderPlanRules.recordAndEventReminders(DOG_M, List.of(legacy), ReminderHistory.EMPTY,
                d("2026-12-01"), launch);
        assertThat(p).extracting(DesiredReminder::kind, DesiredReminder::dueDate).containsExactly(
                tuple(ReminderKind.DEWORM_LEGACY_D_DAY, d("2027-01-15")),
                tuple(ReminderKind.DEWORM_LEGACY_D_PLUS_3, d("2027-01-18")));
        var typed = deworm(DewormType.EXTERNAL, "2026-12-10", "2027-01-10");
        assertThat(ReminderPlanRules.recordAndEventReminders(DOG_M, List.of(legacy, typed), ReminderHistory.EMPTY,
                d("2026-12-11"), launch)).extracting(DesiredReminder::sourceRecordId).containsOnly(typed.id());
        // AC51：回填 F4 Next 28 Nov（早于上线日）→ D+7 5 Dec 也不发
        var f4 = backfill(vaccine(VaccinePreset.F4, null, "2025-11-28", "2026-11-28"));
        assertThat(ReminderPlanRules.recordAndEventReminders(CAT, List.of(f4), ReminderHistory.EMPTY,
                d("2026-12-01"), launch)).isEmpty();
    }

    @Test
    void ac31Ac35PyometraAndHeatDMinus7() {
        var h1 = of(HealthRecordType.MENSTRUATION, "2027-01-01", "2027-07-01");
        var h1b = of(HealthRecordType.MENSTRUATION, "2027-01-03", null);
        var h1c = of(HealthRecordType.MENSTRUATION, "2027-01-06", null);
        var p = plan(DOG_F, List.of(h1, h1b, h1c), "2027-01-07");
        assertThat(p).extracting(DesiredReminder::kind, DesiredReminder::dueDate).containsExactlyInAnyOrder(
                tuple(ReminderKind.HEAT_DOG_D_MINUS_7, d("2027-06-24")),
                tuple(ReminderKind.PYOMETRA_WATCH, d("2027-02-12")));
        var h2 = of(HealthRecordType.MENSTRUATION, "2027-07-01", "2027-12-29");
        assertThat(plan(DOG_F, List.of(h1, h2), "2027-07-02")).extracting(DesiredReminder::kind,
                DesiredReminder::dueDate).containsExactlyInAnyOrder(
                        tuple(ReminderKind.HEAT_DOG_D_MINUS_7, d("2027-12-22")),
                        tuple(ReminderKind.PYOMETRA_WATCH, d("2027-08-12")));
    }

    @Test
    void ac33CatSecondHeatOnceAndAc35OneEpisodeNone() {
        var m1 = of(HealthRecordType.MENSTRUATION, "2027-03-01", null);
        var m20 = of(HealthRecordType.MENSTRUATION, "2027-03-20", null);
        var p = plan(CAT, List.of(m1, m20), "2027-03-21");
        assertThat(p).extracting(DesiredReminder::kind, DesiredReminder::dueDate)
                .containsExactly(tuple(ReminderKind.HEAT_CAT_SECOND, d("2027-03-21")));
        // 已发过 → 10 Apr 的不再有
        var sent = new ReminderHistory(List.of(new ReminderHistory.Row(m20.id(), ReminderKind.HEAT_CAT_SECOND,
                ReminderOrigin.EVENT, ReminderStatus.SENT, null, null, d("2027-03-21"), d("2027-03-21"))));
        var a10 = of(HealthRecordType.MENSTRUATION, "2027-04-10", null);
        assertThat(plan(CAT, List.of(m1, m20, a10), sent, "2027-04-11")).isEmpty();
        // AC35：1、2、4 Mar 是同一次发情 → 无
        assertThat(plan(CAT, List.of(of(HealthRecordType.MENSTRUATION, "2027-03-01", null),
                of(HealthRecordType.MENSTRUATION, "2027-03-02", null),
                of(HealthRecordType.MENSTRUATION, "2027-03-04", null)), "2027-03-05")).isEmpty();
        // 1–7 Mar 后 15 Mar：新周期、60 天内 → 有
        List<HealthRecordSnapshot> daily = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            daily.add(of(HealthRecordType.MENSTRUATION, "2027-03-0" + i, null));
        }
        daily.add(of(HealthRecordType.MENSTRUATION, "2027-03-15", null));
        assertThat(plan(CAT, daily, "2027-03-16")).extracting(DesiredReminder::kind)
                .containsExactly(ReminderKind.HEAT_CAT_SECOND);
    }

    @Test
    void ac34StitchCheckOnlyIfFutureAndNeuteredDropsHeat() {
        var neuter = of(HealthRecordType.NEUTER, "2027-05-03", null);
        assertThat(plan(CAT, List.of(neuter), "2027-05-04")).extracting(DesiredReminder::kind,
                DesiredReminder::dueDate).containsExactly(tuple(ReminderKind.STITCH_CHECK, d("2027-05-13")));
        assertThat(plan(CAT, List.of(neuter), "2027-05-21")).isEmpty();
        var spayed = pet(PetType.DOG, PetSex.FEMALE, "2022-01-01", NeuterStatus.NEUTERED);
        var heat = of(HealthRecordType.MENSTRUATION, "2027-05-01", "2027-11-01");
        assertThat(plan(spayed, List.of(heat), "2027-05-02")).isEmpty();
    }

    @Test
    void maleFirstHeatOnceNeverWithBackfillHeat() {
        var h = of(HealthRecordType.MENSTRUATION, "2027-05-01", null);
        assertThat(plan(DOG_M, List.of(h), "2027-05-02")).extracting(DesiredReminder::kind)
                .containsExactly(ReminderKind.HEAT_MALE_FIRST);
        var old = backfill(of(HealthRecordType.MENSTRUATION, "2026-06-01", null));
        assertThat(plan(DOG_M, List.of(old, h), "2027-05-02")).isEmpty(); // AC52
        assertThat(plan(DOG_M, List.of(h), "2027-05-20")).isEmpty(); // 超过 7 天
    }

    @Test
    void sexNullNoHeatAndOtherSpeciesOnlyRecordPoints() {
        var noSex = pet(PetType.DOG, null, "2022-01-01", null);
        assertThat(plan(noSex, List.of(of(HealthRecordType.MENSTRUATION, "2027-05-01", "2027-11-01")),
                "2027-05-02")).isEmpty();
        var other = pet(PetType.OTHER, PetSex.FEMALE, null, null);
        var custom = of(HealthRecordType.CUSTOM, "2027-05-01", "2027-06-01");
        var neuter = of(HealthRecordType.NEUTER, "2027-05-01", null);
        assertThat(plan(other, List.of(custom, neuter), "2027-05-02")).extracting(DesiredReminder::kind)
                .containsExactly(ReminderKind.CUSTOM_D_DAY);
    }

    @Test
    void readOnlyEmptyAndCatchUpUsedSuppressesNextDate() {
        var f4 = vaccine(VaccinePreset.F4, 2, "2026-11-03", "2026-12-01");
        assertThat(plan(RuleFixtures.readOnly(CAT), List.of(f4), "2026-11-10")).isEmpty();
        var used = new ReminderHistory(List.of(new ReminderHistory.Row(f4.id(), ReminderKind.VACCINE_D_PLUS_7,
                ReminderOrigin.RESUME_CATCH_UP, ReminderStatus.SENT, null, d("2026-12-01"), d("2026-12-03"),
                d("2026-12-03"))));
        assertThat(plan(CAT, List.of(f4), used, "2026-11-10")).isEmpty();
        var rebuiltOnly = new ReminderHistory(List.of(new ReminderHistory.Row(f4.id(), ReminderKind.VACCINE_D_PLUS_7,
                ReminderOrigin.RESUME_CATCH_UP, ReminderStatus.CANCELLED, CancelReason.REBUILT, d("2026-12-01"),
                d("2026-12-03"), d("2026-12-03"))));
        assertThat(plan(CAT, List.of(f4), rebuiltOnly, "2026-11-10")).hasSize(3);
    }

    @Test
    void customDDay() {
        var c = of(HealthRecordType.CUSTOM, "2027-05-01", "2027-06-01");
        var d0 = plan(CAT, List.of(c), "2027-05-02").get(0);
        assertThat(d0.kind()).isEqualTo(ReminderKind.CUSTOM_D_DAY);
        assertThat(d0.point()).isEqualTo(PushPoint.D_DAY);
        assertThat(d0.customName()).isEqualTo("Grooming");
    }

    @Test
    void cancelledNextRunReminderKeepsItsOriginalDueDate() {
        var h = of(HealthRecordType.MENSTRUATION, "2027-05-01", null);
        var skipped = new ReminderHistory(List.of(new ReminderHistory.Row(h.id(), ReminderKind.HEAT_MALE_FIRST,
                ReminderOrigin.EVENT, ReminderStatus.CANCELLED, CancelReason.SKIPPED_CAP, null, d("2027-05-02"),
                d("2027-05-02"))));
        // 次日重建：沿用 5 月 2 日的原定日 → 早于下一跑被过滤，不会以 5 月 3 日复活
        assertThat(plan(DOG_M, List.of(h), skipped, "2027-05-03")).isEmpty();
    }
}
