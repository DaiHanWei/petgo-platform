package com.tailtopia.healthreminder.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.PushPoint;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.rules.PetReminderContext;
import com.tailtopia.healthreminder.rules.ReminderHistory;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.RecordOrigin;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.PetHealthSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（V1.5.0 Story 2.3 · AC4 / AC5.1）：每宠物决策，逐句对 PRD AC37–AC42、AC35 末段，召回日。 */
class HealthReminderPlannerTest {

    private static final AtomicLong IDS = new AtomicLong(1);
    private static final LocalDate MAR1 = LocalDate.parse("2027-03-01");
    private static final PetHealthSnapshot DOG = new PetHealthSnapshot(1L, 7L, PetType.DOG, PetSex.FEMALE,
            LocalDate.parse("2022-01-01"), null, null, false, LocalDate.parse("2025-01-01"), PetStatus.ACTIVE);

    private static HealthReminder row(ReminderKind kind, PushPoint point, String next, String due) {
        HealthReminder r = HealthReminder.pending(1L, 7L, IDS.incrementAndGet(), kind, point,
                kind.family().name().equals("SUGGESTION") || kind == ReminderKind.CHECKUP ? ReminderOrigin.PROFILE
                        : ReminderOrigin.RECORD,
                next == null ? null : LocalDate.parse(next), LocalDate.parse(due));
        ReflectionTestUtils.setField(r, "id", IDS.incrementAndGet());
        return r;
    }

    private static HealthReminderPlanner.PetDayDecision decide(LocalDate day, List<HealthReminder> rows,
            boolean scheduled, boolean recall, Set<LocalDate> futurePending, boolean pregnant) {
        return HealthReminderPlanner.decide(new HealthReminderPlanner.PetDayInput(day, DOG, pregnant, rows,
                new PetReminderContext(DOG, List.of(), ReminderHistory.EMPTY, null), scheduled, recall,
                futurePending, Set.of(), Set.of()));
    }

    private static HealthReminderPlanner.PetDayDecision decide(LocalDate day, HealthReminder... rows) {
        return decide(day, List.of(rows), false, false, Set.of(), false);
    }

    private static List<ReminderKind> sentKinds(HealthReminderPlanner.PetDayDecision d) {
        return d.sendGroups().stream().flatMap(g -> g.rows().stream()).map(HealthReminder::getReminderKind).toList();
    }

    @Test
    void ac37SameTypeSamePointMergesIntoOneGroup() {
        var f4 = row(ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, "2027-03-01", "2027-03-01");
        var rabies = row(ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, "2027-03-01", "2027-03-01");
        var d = decide(MAR1, f4, rabies);
        assertThat(d.sendGroups()).singleElement().satisfies(g -> {
            assertThat(g.rows()).containsExactly(f4, rabies);
            assertThat(g.merged()).isTrue();
        });
        assertThat(d.cancels()).isEmpty();
        assertThat(d.moves()).isEmpty();
    }

    @Test
    void ac37DifferentPointsNotMergedDDayWinsDMinus7MovesToNextDay() {
        var f4 = row(ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, "2027-03-01", "2027-03-01");
        var rabiesD7 = row(ReminderKind.VACCINE_D_MINUS_7, PushPoint.D_MINUS_7, "2027-03-08", "2027-03-01");
        var d = decide(MAR1, f4, rabiesD7);
        assertThat(sentKinds(d)).containsExactly(ReminderKind.VACCINE_D_DAY);
        assertThat(d.moves()).singleElement().satisfies(m -> {
            assertThat(m.row()).isSameAs(rabiesD7);
            assertThat(m.to()).isEqualTo(LocalDate.parse("2027-03-02"));
            assertThat(m.countsAsDeferral()).isTrue();
        });
    }

    @Test
    void ac37BothMovableEarlierNextDateWins() {
        var aPlus7 = row(ReminderKind.VACCINE_D_PLUS_7, PushPoint.D_PLUS_7, "2027-02-22", "2027-03-01");
        var bMinus7 = row(ReminderKind.VACCINE_D_MINUS_7, PushPoint.D_MINUS_7, "2027-03-08", "2027-03-01");
        var d = decide(MAR1, bMinus7, aPlus7);
        assertThat(d.sendGroups()).singleElement().satisfies(g -> assertThat(g.rows()).containsExactly(aPlus7));
        assertThat(d.moves()).singleElement().satisfies(m -> assertThat(m.row()).isSameAs(bMinus7));
    }

    @Test
    void ac38VaccineBeatsDewormDDaySkippedCap() {
        var vac = row(ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, "2027-03-01", "2027-03-01");
        var dew = row(ReminderKind.DEWORM_INTERNAL_D_DAY, PushPoint.D_DAY, "2027-03-01", "2027-03-01");
        var d = decide(MAR1, vac, dew);
        assertThat(sentKinds(d)).containsExactly(ReminderKind.VACCINE_D_DAY);
        assertThat(d.cancels()).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(CancelReason.SKIPPED_CAP);
            assertThat(c.displacedBy()).isEqualTo("VACCINE_D_DAY");
        });
    }

    @Test
    void ac39TierDecidesVaccineDMinus7BeatsDewormDDay() {
        var rabiesD7 = row(ReminderKind.VACCINE_D_MINUS_7, PushPoint.D_MINUS_7, "2027-03-08", "2027-03-01");
        var dew = row(ReminderKind.DEWORM_INTERNAL_D_DAY, PushPoint.D_DAY, "2027-03-01", "2027-03-01");
        var d = decide(MAR1, dew, rabiesD7);
        assertThat(sentKinds(d)).containsExactly(ReminderKind.VACCINE_D_MINUS_7);
        assertThat(d.cancels()).singleElement().satisfies(c -> {
            assertThat(c.row()).isSameAs(dew);
            assertThat(c.reason()).isEqualTo(CancelReason.SKIPPED_CAP);
            assertThat(c.displacedBy()).isEqualTo("VACCINE_D_MINUS_7");
        });
    }

    @Test
    void ac40BirthdayDay() {
        var pyo = row(ReminderKind.PYOMETRA_WATCH, PushPoint.NONE, null, "2027-03-01");
        var vetD1 = row(ReminderKind.VET_VISIT_D_MINUS_1, PushPoint.D_MINUS_1, "2027-03-02", "2027-03-01");
        var custom = row(ReminderKind.CUSTOM_D_DAY, PushPoint.D_DAY, "2027-03-01", "2027-03-01");
        var vac = row(ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, "2027-03-01", "2027-03-01");
        var checkup = row(ReminderKind.CHECKUP, PushPoint.NONE, null, "2027-03-01");
        var d = decide(MAR1, List.of(pyo, vetD1, custom, vac, checkup), true, false, Set.of(), false);
        assertThat(sentKinds(d)).containsExactlyInAnyOrder(ReminderKind.PYOMETRA_WATCH,
                ReminderKind.VET_VISIT_D_MINUS_1, ReminderKind.CUSTOM_D_DAY);
        assertThat(d.cancels()).singleElement().satisfies(c -> {
            assertThat(c.row()).isSameAs(vac);
            assertThat(c.reason()).isEqualTo(CancelReason.SKIPPED_SCHEDULED_PUSH);
            assertThat(c.displacedBy()).isEqualTo("scheduled_push");
        });
        assertThat(d.moves()).singleElement().satisfies(m -> {
            assertThat(m.row()).isSameAs(checkup);
            assertThat(m.to()).isEqualTo(LocalDate.parse("2027-03-02"));
        });
    }

    @Test
    void ac41TiersOneAndTwoAreExempt() {
        var vetD1 = row(ReminderKind.VET_VISIT_D_MINUS_1, PushPoint.D_MINUS_1, "2027-03-02", "2027-03-01");
        var vac = row(ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, "2027-03-01", "2027-03-01");
        assertThat(sentKinds(decide(MAR1, vetD1, vac))).containsExactlyInAnyOrder(ReminderKind.VET_VISIT_D_MINUS_1,
                ReminderKind.VACCINE_D_DAY);

        var pyo = row(ReminderKind.PYOMETRA_WATCH, PushPoint.NONE, null, "2027-03-01");
        var vetDDay = row(ReminderKind.VET_VISIT_D_DAY, PushPoint.D_DAY, "2027-03-01", "2027-03-01");
        assertThat(decide(MAR1, pyo, vetDDay).sendGroups()).hasSize(2);

        var custom = row(ReminderKind.CUSTOM_D_DAY, PushPoint.D_DAY, "2027-03-01", "2027-03-01");
        var vetDDay2 = row(ReminderKind.VET_VISIT_D_DAY, PushPoint.D_DAY, "2027-03-01", "2027-03-01");
        assertThat(decide(MAR1, vetDDay2, custom).sendGroups()).hasSize(2);

        var visitA = row(ReminderKind.VET_VISIT_D_MINUS_1, PushPoint.D_MINUS_1, "2027-03-02", "2027-03-01");
        var visitB = row(ReminderKind.VET_VISIT_D_DAY, PushPoint.D_DAY, "2027-03-01", "2027-03-01");
        assertThat(decide(MAR1, visitA, visitB).sendGroups()).hasSize(2);

        var custom2 = row(ReminderKind.CUSTOM_D_DAY, PushPoint.D_DAY, "2027-03-01", "2027-03-01");
        var vacD7 = row(ReminderKind.VACCINE_D_MINUS_7, PushPoint.D_MINUS_7, "2027-03-08", "2027-03-01");
        var firstDeworm = row(ReminderKind.FIRST_DEWORM, PushPoint.NONE, null, "2027-03-01");
        var d = decide(MAR1, custom2, vacD7, firstDeworm);
        assertThat(sentKinds(d)).containsExactlyInAnyOrder(ReminderKind.CUSTOM_D_DAY, ReminderKind.VACCINE_D_MINUS_7);
        assertThat(d.moves()).singleElement().satisfies(m -> {
            assertThat(m.row()).isSameAs(firstDeworm);
            assertThat(m.to()).isEqualTo(LocalDate.parse("2027-03-02"));
        });
    }

    @Test
    void ac42MovedReminderMovesAgainWithinThreeDaysThenExpires() {
        LocalDate mar2 = LocalDate.parse("2027-03-02");
        var plus7 = row(ReminderKind.VACCINE_D_PLUS_7, PushPoint.D_PLUS_7, "2027-02-22", "2027-03-01");
        plus7.deferTo(mar2);
        var otherDDay = row(ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, "2027-03-02", "2027-03-02");
        var d = decide(mar2, List.of(plus7, otherDDay), false, false, Set.of(), false);
        assertThat(sentKinds(d)).containsExactly(ReminderKind.VACCINE_D_DAY);
        assertThat(d.moves()).singleElement().satisfies(m -> assertThat(m.to()).isEqualTo(LocalDate.parse("2027-03-03")));

        var plus7b = row(ReminderKind.VACCINE_D_PLUS_7, PushPoint.D_PLUS_7, "2027-02-22", "2027-03-01");
        plus7b.deferTo(mar2);
        var otherDDay2 = row(ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, "2027-03-02", "2027-03-02");
        var expired = decide(mar2, List.of(plus7b, otherDDay2), false, false,
                Set.of(LocalDate.parse("2027-03-03"), LocalDate.parse("2027-03-04")), false);
        assertThat(expired.cancels()).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(CancelReason.EXPIRED_DEFER);
            assertThat(c.displacedBy()).isEqualTo("VACCINE_D_DAY");
        });
    }

    @Test
    void recallDayDefersOnlyTierEight() {
        var firstDeworm = row(ReminderKind.FIRST_DEWORM, PushPoint.NONE, null, "2027-03-01");
        var checkup = row(ReminderKind.CHECKUP, PushPoint.NONE, null, "2027-03-01");
        var d = decide(MAR1, List.of(firstDeworm, checkup), false, true, Set.of(), false);
        assertThat(sentKinds(d)).containsExactly(ReminderKind.CHECKUP);
        assertThat(d.moves()).singleElement().satisfies(m -> assertThat(m.row()).isSameAs(firstDeworm));
    }

    @Test
    void ac35PregnantPausesVaccinePyometraHeatHoldsFirstVaccineDewormGoes() {
        var vac = row(ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, "2027-03-01", "2027-03-01");
        var pyo = row(ReminderKind.PYOMETRA_WATCH, PushPoint.NONE, null, "2027-03-01");
        var heat = row(ReminderKind.HEAT_DOG_D_MINUS_7, PushPoint.D_MINUS_7, "2027-03-08", "2027-03-01");
        var first = row(ReminderKind.FIRST_VACCINE, PushPoint.NONE, null, "2027-03-01");
        var dew = row(ReminderKind.DEWORM_INTERNAL_D_DAY, PushPoint.D_DAY, "2027-03-01", "2027-03-01");
        var d = decide(MAR1, List.of(vac, pyo, heat, first, dew), false, false, Set.of(), true);
        assertThat(d.cancels()).extracting(HealthReminderPlanner.Cancel::reason)
                .containsOnly(CancelReason.PAUSED).hasSize(3);
        assertThat(d.moves()).singleElement().satisfies(m -> {
            assertThat(m.row()).isSameAs(first);
            assertThat(m.countsAsDeferral()).isFalse();
        });
        assertThat(sentKinds(d)).containsExactly(ReminderKind.DEWORM_INTERNAL_D_DAY);
    }

    @Test
    void readOnlyCancelsAllAndProfileRecheckSatisfied() {
        var ro = new PetHealthSnapshot(1L, 7L, PetType.DOG, PetSex.FEMALE, null, null, null, false,
                LocalDate.parse("2025-01-01"), PetStatus.READ_ONLY);
        var r = row(ReminderKind.CUSTOM_D_DAY, PushPoint.D_DAY, "2027-03-01", "2027-03-01");
        var d = HealthReminderPlanner.decide(new HealthReminderPlanner.PetDayInput(MAR1, ro, false, List.of(r),
                null, false, false, Set.of(), Set.of(), Set.of()));
        assertThat(d.cancels()).singleElement().satisfies(c -> assertThat(c.reason())
                .isEqualTo(CancelReason.READ_ONLY));

        var firstDeworm = row(ReminderKind.FIRST_DEWORM, PushPoint.NONE, null, "2027-03-01");
        var dewormRecord = new HealthRecordSnapshot(99L, HealthRecordType.DEWORM, null, null, null, null, null,
                LocalDate.parse("2027-02-20"), null, null, RecordOrigin.APP, Instant.EPOCH);
        var d2 = HealthReminderPlanner.decide(new HealthReminderPlanner.PetDayInput(MAR1, DOG, false,
                List.of(firstDeworm), new PetReminderContext(DOG, List.of(dewormRecord), ReminderHistory.EMPTY, null),
                false, false, Set.of(), Set.of(), Set.of()));
        assertThat(d2.cancels()).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(CancelReason.SATISFIED);
            assertThat(c.byRecordId()).isEqualTo(99L);
        });
    }

    @Test
    void heldFirstVaccineTakesNextDaySoDeferralsSkipIt() {
        var first = row(ReminderKind.FIRST_VACCINE, PushPoint.NONE, null, "2027-03-01");
        var stitch = row(ReminderKind.STITCH_CHECK, PushPoint.NONE, null, "2027-03-01");
        var d = decide(MAR1, List.of(first, stitch), true, false, Set.of(), true);
        assertThat(d.moves()).anySatisfy(m -> {
            assertThat(m.row()).isSameAs(stitch);
            assertThat(m.to()).isEqualTo(LocalDate.parse("2027-03-03"));
        });
    }
}
