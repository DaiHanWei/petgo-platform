package com.tailtopia.healthreminder.rules;

import static com.tailtopia.healthreminder.rules.RuleFixtures.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.NeuterStatus;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.health.PetHealthSnapshot;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0（V1.5.0 Story 2.2 · AC5 / AC6 的规则部分）：排队资格、顺序、发送日再校验，PRD AC27 / AC28。 */
class LaunchQueueRulesTest {

    private static final LocalDate LAUNCH = d("2026-12-01");

    private static PetHealthSnapshot pet(long id, PetType type, PetSex sex, String birthday, String created,
            NeuterStatus neuter) {
        return new PetHealthSnapshot(id, 7L, type, sex, birthday == null ? null : d(birthday), neuter, null, false,
                d(created), PetStatus.ACTIVE, d(created).atTime(8, 0).atZone(ScheduleWindow.WIB).toInstant(), null);
    }

    private static PetReminderContext ctx(PetHealthSnapshot p) {
        return new PetReminderContext(p, List.of(), ReminderHistory.EMPTY, null);
    }

    @Test
    void ac28OrderKittenVaccineDogDewormKittenDewormDogCheckup() {
        var dog = pet(2L, PetType.DOG, PetSex.MALE, "2023-12-01", "2024-12-01", null);
        var kitten = pet(3L, PetType.CAT, PetSex.FEMALE, "2026-08-01", "2026-11-01", null);
        assertThat(LaunchQueueRules.items(List.of(ctx(kitten), ctx(dog)), LAUNCH))
                .extracting(LaunchQueueRules.QueueItem::petId, LaunchQueueRules.QueueItem::kind)
                .containsExactly(tuple(3L, ReminderKind.FIRST_VACCINE), tuple(2L, ReminderKind.FIRST_DEWORM),
                        tuple(3L, ReminderKind.FIRST_DEWORM), tuple(2L, ReminderKind.CHECKUP));
    }

    @Test
    void ac28PetAddedThreeDaysBeforeLaunchNotQueued() {
        var fresh = pet(4L, PetType.DOG, PetSex.MALE, "2020-01-01", "2026-11-28", null);
        assertThat(LaunchQueueRules.items(List.of(ctx(fresh)), LAUNCH)).isEmpty();
    }

    @Test
    void ac27UnknownThreeYearCatNoSpayEightMonthIntactFemaleYes() {
        var adult = pet(5L, PetType.CAT, PetSex.FEMALE, "2023-11-01", "2024-01-01", NeuterStatus.UNKNOWN);
        assertThat(LaunchQueueRules.items(List.of(ctx(adult)), LAUNCH)).extracting(LaunchQueueRules.QueueItem::kind)
                .doesNotContain(ReminderKind.SPAY_NEUTER_SUGGESTION);
        var young = pet(6L, PetType.CAT, PetSex.FEMALE, "2026-04-01", "2026-05-01", null);
        assertThat(LaunchQueueRules.items(List.of(ctx(young)), LAUNCH)).extracting(LaunchQueueRules.QueueItem::kind)
                .contains(ReminderKind.SPAY_NEUTER_SUGGESTION);
    }

    @Test
    void recheckOnSendDay() {
        var kitten = pet(3L, PetType.CAT, PetSex.FEMALE, "2026-08-01", "2026-11-01", null);
        assertThat(LaunchQueueRules.recheck(ReminderKind.FIRST_VACCINE, ctx(kitten), d("2026-12-05"))).isEmpty();
        // 已满 6 个月
        assertThat(LaunchQueueRules.recheck(ReminderKind.FIRST_VACCINE, ctx(kitten), d("2027-02-01")))
                .contains(CancelReason.QUEUE_RECHECK);
        // 怀孕
        var pregnant = new PetHealthSnapshot(3L, 7L, PetType.CAT, PetSex.FEMALE, d("2026-08-01"), null, null, true,
                d("2026-11-01"), PetStatus.ACTIVE, null, null);
        assertThat(LaunchQueueRules.recheck(ReminderKind.FIRST_VACCINE, ctx(pregnant), d("2026-12-05")))
                .contains(CancelReason.QUEUE_RECHECK);
        // 期间记了驱虫
        var withDeworm = new PetReminderContext(kitten, List.of(RuleFixtures.of(HealthRecordType.DEWORM,
                "2026-12-03", null)), ReminderHistory.EMPTY, null);
        assertThat(LaunchQueueRules.recheck(ReminderKind.FIRST_DEWORM, withDeworm, d("2026-12-05")))
                .contains(CancelReason.QUEUE_RECHECK);
        // 体检：期间有到访 → 作废
        var dog = pet(2L, PetType.DOG, PetSex.MALE, "2023-12-01", "2024-12-01", null);
        var visited = new PetReminderContext(dog, List.of(), ReminderHistory.EMPTY, d("2026-12-03"));
        assertThat(LaunchQueueRules.recheck(ReminderKind.CHECKUP, ctx(dog), d("2026-12-05"))).isEmpty();
        assertThat(LaunchQueueRules.recheck(ReminderKind.CHECKUP, visited, d("2026-12-05")))
                .contains(CancelReason.QUEUE_RECHECK);
    }
}
