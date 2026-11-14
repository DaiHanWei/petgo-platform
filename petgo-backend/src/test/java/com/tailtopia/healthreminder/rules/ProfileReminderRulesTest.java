package com.tailtopia.healthreminder.rules;

import static com.tailtopia.healthreminder.rules.RuleFixtures.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.domain.ReminderStatus;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.NeuterStatus;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.PetHealthSnapshot;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0（V1.5.0 Story 2.2 · AC3）：档案类建议，PRD AC17 / AC23 / AC24 / AC29、UJ-3。 */
class ProfileReminderRulesTest {

    private static final LocalDate LAUNCH = d("2026-12-01");

    private static Instant at(String day, int hour) {
        return LocalDate.parse(day).atTime(hour, 0).atZone(ScheduleWindow.WIB).toInstant();
    }

    private static PetHealthSnapshot pet(PetType type, PetSex sex, String birthday, String created, Instant sexSetAt) {
        Instant c = at(created, 8);
        return new PetHealthSnapshot(1L, 7L, type, sex, birthday == null ? null : d(birthday), null, null, false,
                d(created), PetStatus.ACTIVE, c, sexSetAt);
    }

    private static List<DesiredReminder> desired(PetHealthSnapshot p, List<HealthRecordSnapshot> recs) {
        return ProfileReminderRules.desired(new PetReminderContext(p, recs, ReminderHistory.EMPTY, null), LAUNCH);
    }

    @Test
    void ac23KittenFirstVaccineAtSixWeeks() {
        var kitten = pet(PetType.CAT, PetSex.FEMALE, "2027-01-01", "2027-01-05", null);
        assertThat(desired(kitten, List.of())).extracting(DesiredReminder::kind, DesiredReminder::dueDate)
                .contains(tuple(ReminderKind.FIRST_VACCINE, d("2027-02-12")));
    }

    @Test
    void ac29EightMonthCatAddedMarchFirst() {
        var cat = pet(PetType.CAT, PetSex.FEMALE, "2026-07-01", "2027-03-01", null);
        assertThat(desired(cat, List.of())).extracting(DesiredReminder::kind, DesiredReminder::dueDate)
                .contains(tuple(ReminderKind.FIRST_DEWORM, d("2027-03-08")),
                        tuple(ReminderKind.SPAY_NEUTER_SUGGESTION, d("2027-03-15")))
                .extracting(t -> t.toList().get(0)).doesNotContain(ReminderKind.FIRST_VACCINE);
        var old = pet(PetType.CAT, PetSex.FEMALE, "2026-02-01", "2027-03-01", null);
        assertThat(desired(old, List.of())).extracting(DesiredReminder::kind)
                .doesNotContain(ReminderKind.SPAY_NEUTER_SUGGESTION);
    }

    @Test
    void ac29SexSetLaterDelaysSpay() {
        var setThird = pet(PetType.CAT, PetSex.FEMALE, "2026-08-01", "2027-03-01", at("2027-03-03", 9));
        assertThat(spayDate(setThird)).isEqualTo(d("2027-03-15"));
        var setTwentieth = pet(PetType.CAT, PetSex.FEMALE, "2026-08-01", "2027-03-01", at("2027-03-20", 15));
        assertThat(spayDate(setTwentieth)).isEqualTo(d("2027-03-21"));
        var noSex = pet(PetType.CAT, null, "2026-08-01", "2027-03-01", null);
        assertThat(spayDate(noSex)).isNull();
    }

    @Test
    void uj3ThreeMonthKittenNextRunVaccineDay7DewormFiveMonthSpay() {
        var kitten = pet(PetType.CAT, PetSex.FEMALE, "2026-12-01", "2027-03-01", null);
        assertThat(desired(kitten, List.of())).extracting(DesiredReminder::kind, DesiredReminder::dueDate)
                .containsExactlyInAnyOrder(tuple(ReminderKind.FIRST_VACCINE, d("2027-03-01")),
                        tuple(ReminderKind.FIRST_DEWORM, d("2027-03-08")),
                        tuple(ReminderKind.SPAY_NEUTER_SUGGESTION, d("2027-05-01")),
                        tuple(ReminderKind.CHECKUP, d("2028-03-01")));
    }

    @Test
    void ac17DogSixMonthSpayAndSevenYearSenior() {
        var puppy = pet(PetType.DOG, PetSex.MALE, "2026-12-01", "2027-01-10", null);
        assertThat(spayDate(puppy)).isEqualTo(d("2027-06-01"));
        var dog = pet(PetType.DOG, PetSex.MALE, "2019-06-01", "2026-12-15", null);
        // 建档 2026-12-15（7.5 岁，已 senior）→ base + 6 个月
        assertThat(desired(dog, List.of())).extracting(DesiredReminder::kind, DesiredReminder::dueDate)
                .contains(tuple(ReminderKind.CHECKUP, d("2027-06-15")));
    }

    @Test
    void ac24NoBirthdayOnlyDewormAndCheckupRecordsSkip() {
        var old = pet(PetType.CAT, PetSex.FEMALE, null, "2026-12-15", null);
        assertThat(desired(old, List.of())).extracting(DesiredReminder::kind)
                .containsExactlyInAnyOrder(ReminderKind.FIRST_DEWORM, ReminderKind.CHECKUP);
        var withDeworm = RuleFixtures.of(HealthRecordType.DEWORM, "2026-12-16", null);
        assertThat(desired(old, List.of(withDeworm))).extracting(DesiredReminder::kind)
                .containsExactly(ReminderKind.CHECKUP);
    }

    @Test
    void beforeUserLaunchGoesToQueueAndSentOnceNotRegenerated() {
        var cat = pet(PetType.CAT, PetSex.FEMALE, "2026-10-01", "2026-11-01", null);
        // 首驱虫 11-08 < 上线日 12-01 → 不在这里（归排队）
        assertThat(desired(cat, List.of())).extracting(DesiredReminder::kind)
                .doesNotContain(ReminderKind.FIRST_DEWORM);
        var kitten = pet(PetType.CAT, PetSex.FEMALE, "2027-01-01", "2027-01-05", null);
        var sent = new ReminderHistory(List.of(new ReminderHistory.Row(null, ReminderKind.FIRST_VACCINE,
                ReminderOrigin.PROFILE, ReminderStatus.SENT, null, null, d("2027-02-12"), d("2027-02-12"))));
        assertThat(ProfileReminderRules.desired(new PetReminderContext(kitten, List.of(), sent, null), LAUNCH))
                .extracting(DesiredReminder::kind).doesNotContain(ReminderKind.FIRST_VACCINE);
    }

    @Test
    void neuteredOtherSpeciesReadOnlyAndNoLaunch() {
        var neutered = new PetHealthSnapshot(1L, 7L, PetType.CAT, PetSex.FEMALE, d("2026-12-01"),
                NeuterStatus.NEUTERED, null, false, d("2027-01-01"), PetStatus.ACTIVE, null, null);
        assertThat(spayDate(neutered)).isNull();
        var other = pet(PetType.OTHER, PetSex.FEMALE, "2027-01-01", "2027-01-05", null);
        assertThat(desired(other, List.of())).isEmpty();
        var kitten = pet(PetType.CAT, PetSex.FEMALE, "2027-01-01", "2027-01-05", null);
        assertThat(ProfileReminderRules.desired(new PetReminderContext(kitten, List.of(), ReminderHistory.EMPTY,
                null), null)).isEmpty();
    }

    private static LocalDate spayDate(PetHealthSnapshot p) {
        return ProfileReminderRules.spayNormal(new PetReminderContext(p, List.of(), ReminderHistory.EMPTY, null));
    }

    @Test
    void createdOnSixWeekOrTriggerDayAfterRunUsesLaterDate() {
        // 2026-11-12 满 6 周，当天 20:00 建档 → 首针在下一跑 11-13（不是已过的 11-12）
        var kitten = new PetHealthSnapshot(1L, 7L, PetType.CAT, PetSex.FEMALE, d("2026-10-01"), null, null, false,
                d("2026-11-12"), PetStatus.ACTIVE, at("2026-11-12", 20), null);
        assertThat(ProfileReminderRules.firstVaccineNormal(new PetReminderContext(kitten, List.of(),
                ReminderHistory.EMPTY, null))).isEqualTo(d("2026-11-13"));
        // 触发日当天建档 → 建档 + 14 天
        var cat = pet(PetType.CAT, PetSex.FEMALE, "2026-06-14", "2026-11-14", null);
        assertThat(spayDate(cat)).isEqualTo(d("2026-11-28"));
    }

    @Test
    void pastDueCheckupNotBeforeLaunchIsSentNextRun() {
        var dog = pet(PetType.DOG, PetSex.MALE, "2022-01-01", "2026-12-01", null);
        var ctx = new PetReminderContext(dog, List.of(), ReminderHistory.EMPTY, d("2025-12-05"));
        // 到访 2025-12-05 → 到期 2026-12-05 ≥ 上线日 12-01，但下一跑已是 12-10 → 改在 12-10 发
        assertThat(ProfileReminderRules.desired(ctx, LAUNCH, d("2026-12-10")))
                .extracting(DesiredReminder::kind, DesiredReminder::dueDate)
                .contains(tuple(ReminderKind.CHECKUP, d("2026-12-10")));
    }
}
