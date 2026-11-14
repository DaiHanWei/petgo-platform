package com.tailtopia.healthreminder.rules;

import static com.tailtopia.healthreminder.rules.RuleFixtures.d;
import static com.tailtopia.healthreminder.rules.RuleFixtures.deworm;
import static com.tailtopia.healthreminder.rules.RuleFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.PushPoint;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.PetType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0（V1.5.0 Story 2.1 · AC4）：对账 —— 保留 / 取消原因 / 插入 / 最多送一次 / 已发补满足。 */
class HealthReminderReconcileTest {

    private static DesiredReminder want(Long source, ReminderKind kind, PushPoint point, String due) {
        return DesiredReminder.simple(source, kind, point, ReminderOrigin.RECORD, null, d(due));
    }

    private static ReminderReconciler.CancelContext ctx(SatisfactionLookup s, Long deleted) {
        return new ReminderReconciler.CancelContext(false, false, deleted, s);
    }

    @Test
    void sameKeyKeptWithDeferralNewInsertedMissingCancelled() {
        HealthReminder keep = row(10L, ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, "2027-01-10",
                ReminderOrigin.RECORD);
        keep.deferTo(d("2027-01-12"));
        HealthReminder stale = row(10L, ReminderKind.VACCINE_D_PLUS_7, PushPoint.D_PLUS_7, "2027-01-17",
                ReminderOrigin.RECORD);
        var res = ReminderReconciler.diff(List.of(keep, stale), List.of(),
                List.of(want(10L, ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, "2027-01-10"),
                        want(11L, ReminderKind.CUSTOM_D_DAY, PushPoint.D_DAY, "2027-02-01")),
                ctx(SatisfactionLookup.of(List.of(), PetType.CAT), null));
        assertThat(res.kept()).containsExactly(keep);
        assertThat(keep.getSendDate()).isEqualTo(d("2027-01-12")); // 顺延状态保留
        assertThat(res.cancels()).singleElement().satisfies(c -> {
            assertThat(c.row()).isSameAs(stale);
            assertThat(c.reason()).isEqualTo(CancelReason.REBUILT);
        });
        assertThat(res.inserts()).extracting(DesiredReminder::sourceRecordId).containsExactly(11L);
    }

    @Test
    void sentSameKeyBlocksInsertAtMostOnce() {
        HealthReminder sent = row(10L, ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, "2027-01-10",
                ReminderOrigin.RECORD);
        sent.markSent(1L, Instant.EPOCH);
        var res = ReminderReconciler.diff(List.of(), List.of(sent),
                List.of(want(10L, ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, "2027-01-10")),
                ctx(SatisfactionLookup.of(List.of(), PetType.CAT), null));
        assertThat(res.inserts()).isEmpty();
    }

    @Test
    void cancelReasons() {
        var internal = deworm(DewormType.INTERNAL, "2027-01-01", "2027-04-01");
        var fw = deworm(DewormType.FLEA_WORM, "2027-02-01", "2027-03-01");
        var lookup = SatisfactionLookup.of(List.of(internal, fw), PetType.DOG);
        HealthReminder covered = row(internal.id(), ReminderKind.DEWORM_INTERNAL_D_DAY, PushPoint.D_DAY,
                "2027-04-01", ReminderOrigin.RECORD);
        HealthReminder deleted = row(99L, ReminderKind.CUSTOM_D_DAY, PushPoint.D_DAY, "2027-04-01",
                ReminderOrigin.RECORD);
        var res = ReminderReconciler.diff(List.of(covered, deleted), List.of(), List.of(), ctx(lookup, 99L));
        assertThat(res.cancels()).extracting(ReminderReconciler.Cancel::reason)
                .containsExactly(CancelReason.SATISFIED, CancelReason.RECORD_DELETED);
        assertThat(res.cancels().get(0).satisfiedByRecordId()).isEqualTo(fw.id());

        HealthReminder heat = row(5L, ReminderKind.PYOMETRA_WATCH, PushPoint.NONE, "2027-04-01",
                ReminderOrigin.EVENT);
        var neutered = ReminderReconciler.diff(List.of(heat), List.of(), List.of(),
                new ReminderReconciler.CancelContext(false, true, null, lookup));
        assertThat(neutered.cancels().get(0).reason()).isEqualTo(CancelReason.NEUTERED);
        var readOnly = ReminderReconciler.diff(List.of(heat), List.of(), List.of(),
                new ReminderReconciler.CancelContext(true, true, null, lookup));
        assertThat(readOnly.cancels().get(0).reason()).isEqualTo(CancelReason.READ_ONLY);
    }

    @Test
    void sentRecordReminderMarkedSatisfiedOnce() {
        var old = deworm(DewormType.INTERNAL, "2027-01-01", "2027-04-01");
        var newer = deworm(DewormType.INTERNAL, "2027-04-03", "2027-07-03");
        HealthReminder sent = row(old.id(), ReminderKind.DEWORM_INTERNAL_D_DAY, PushPoint.D_DAY, "2027-04-01",
                ReminderOrigin.RECORD);
        sent.markSent(1L, Instant.EPOCH);
        var lookup = SatisfactionLookup.of(List.of(old, newer), PetType.DOG);
        var res = ReminderReconciler.diff(List.of(), List.of(sent), List.of(), ctx(lookup, null));
        assertThat(res.satisfiedSent()).singleElement().satisfies(m -> assertThat(m.byRecordId())
                .isEqualTo(newer.id()));
        sent.markSatisfied(Instant.EPOCH, newer.id());
        assertThat(ReminderReconciler.diff(List.of(), List.of(sent), List.of(), ctx(lookup, null)).satisfiedSent())
                .isEmpty();
    }

    @Test
    void pendingOutsideWindowOccupiesKey() {
        HealthReminder queued = row(null, ReminderKind.FIRST_VACCINE, PushPoint.NONE, "2027-01-01",
                ReminderOrigin.LAUNCH_QUEUE);
        var res = ReminderReconciler.diff(List.of(), List.of(queued),
                List.of(DesiredReminder.simple(null, ReminderKind.FIRST_VACCINE, PushPoint.NONE,
                        ReminderOrigin.PROFILE, null, d("2027-01-01"))),
                ctx(SatisfactionLookup.of(List.of(), PetType.CAT), null));
        assertThat(res.inserts()).isEmpty();
    }
}
