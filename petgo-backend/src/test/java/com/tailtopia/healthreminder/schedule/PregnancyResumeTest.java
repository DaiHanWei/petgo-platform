package com.tailtopia.healthreminder.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.PushPoint;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.domain.ReminderStatus;
import com.tailtopia.healthreminder.rules.ReminderHistory;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.RecordOrigin;
import com.tailtopia.profile.domain.VaccinePreset;
import com.tailtopia.profile.health.HealthDates;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.PetHealthSnapshot;
import com.tailtopia.profile.health.PregnancyFlag;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** L0（V1.5.0 Story 2.3 · AC5）：恢复表，逐句对 PRD AC45（Next date 2027-06-01）。 */
class PregnancyResumeTest {

    private static final PetHealthSnapshot CAT = new PetHealthSnapshot(1L, 7L, PetType.CAT, PetSex.FEMALE,
            LocalDate.parse("2022-01-01"), null, null, false, LocalDate.parse("2025-01-01"), PetStatus.ACTIVE);
    private static final LocalDate N = LocalDate.parse("2027-06-01");
    private static final HealthRecordSnapshot F4 = new HealthRecordSnapshot(10L, HealthRecordType.VACCINE,
            VaccinePreset.F4, "F4 (Tetracat)", 2, null, null, LocalDate.parse("2026-06-01"), N, null, RecordOrigin.APP,
            Instant.EPOCH);

    private static Instant wib(String dateTime) {
        return java.time.LocalDateTime.parse(dateTime).atZone(ScheduleWindow.WIB).toInstant();
    }

    private static HealthReminder pendingPlus7() {
        HealthReminder r = HealthReminder.pending(1L, 7L, 10L, ReminderKind.VACCINE_D_PLUS_7, PushPoint.D_PLUS_7,
                ReminderOrigin.RECORD, N, N.plusDays(7));
        return r;
    }

    private static ResumeRules.Plan plan(Instant clearedAt, List<HealthReminder> pending) {
        return ResumeRules.plan(CAT, List.of(F4), ReminderHistory.EMPTY, pending,
                HealthDates.nextRunDate(clearedAt), LocalDate.parse("2026-12-01"));
    }

    @ParameterizedTest(name = "清除于 {0} → 无补发")
    @CsvSource({"2027-05-20T08:00", "2027-05-28T15:00", "2027-06-01T08:00", "2027-06-15T15:00"})
    void noCatchUp(String clearedAt) {
        var p = plan(wib(clearedAt), List.of(pendingPlus7()));
        assertThat(p.catchUps()).isEmpty();
        assertThat(p.cancelCatchUpSent()).isEmpty();
    }

    @ParameterizedTest(name = "清除于 {0} → {1} 补发，hari={2}")
    @CsvSource({
        "2027-06-01T15:00, 2027-06-02, 1",
        "2027-06-05T15:00, 2027-06-06, 5",
        "2027-06-05T08:00, 2027-06-05, 4",
        "2027-06-10T15:00, 2027-06-11, 10",
        "2027-06-14T15:00, 2027-06-15, 14"})
    void catchUpOnR(String clearedAt, String r, long hari) {
        HealthReminder plus7 = pendingPlus7();
        var p = plan(wib(clearedAt), List.of(plus7));
        assertThat(p.catchUps()).singleElement().satisfies(c -> {
            assertThat(c.sendDate()).isEqualTo(LocalDate.parse(r));
            assertThat(c.nextDate()).isEqualTo(N);
            assertThat(java.time.temporal.ChronoUnit.DAYS.between(c.nextDate(), c.sendDate())).isEqualTo(hari);
        });
        // R 在 D+1~D+6：未到的 D+7（6/8）一并取消；R ≥ D+7 时 D+7 早已过（不在 PENDING 里）
        if (hari < 7) {
            assertThat(p.cancelCatchUpSent()).containsExactly(plus7);
        }
    }

    @Test
    void alreadyUsedCatchUpNotRepeated() {
        var used = new ReminderHistory(List.of(new ReminderHistory.Row(10L, ReminderKind.VACCINE_D_PLUS_7,
                ReminderOrigin.RESUME_CATCH_UP, ReminderStatus.PENDING, null, N, LocalDate.parse("2027-06-06"),
                LocalDate.parse("2027-06-06"))));
        assertThat(ResumeRules.plan(CAT, List.of(F4), used, List.of(), LocalDate.parse("2027-06-06"),
                LocalDate.parse("2026-12-01")).catchUps()).isEmpty();
    }

    @Test
    void ac45AutoClearAtMidnightWibOnFourMonthDay() {
        Instant setAt = wib("2027-01-01T09:00");
        assertThat(PregnancyFlag.autoOffDate(setAt)).isEqualTo(LocalDate.parse("2027-05-01"));
        Instant clearedAt = LocalDate.parse("2027-05-01").atStartOfDay(ScheduleWindow.WIB).toInstant();
        assertThat(HealthDates.nextRunDate(clearedAt)).isEqualTo(LocalDate.parse("2027-05-01"));
    }
}
