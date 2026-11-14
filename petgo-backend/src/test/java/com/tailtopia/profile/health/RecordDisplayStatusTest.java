package com.tailtopia.profile.health;

import static com.tailtopia.profile.health.HealthFixtures.backfill;
import static com.tailtopia.profile.health.HealthFixtures.d;
import static com.tailtopia.profile.health.HealthFixtures.deworm;
import static com.tailtopia.profile.health.HealthFixtures.pet;
import static com.tailtopia.profile.health.HealthFixtures.rec;
import static com.tailtopia.profile.health.HealthFixtures.vaccine;
import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.NeuterStatus;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.VaccinePreset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0（V1.5.0 Story 1.2 · AC5.3–5.4）：列表显示状态，用例写 PRD AC 编号。 */
class RecordDisplayStatusTest {

    private static final PetHealthSnapshot CAT = pet(PetType.CAT, PetSex.FEMALE, d("2024-01-01"), null);
    private static final PetHealthSnapshot DOG_F = pet(PetType.DOG, PetSex.FEMALE, d("2024-01-01"), null);

    private static RecordDisplayStatus.Display of(List<HealthRecordSnapshot> list, PetHealthSnapshot pet,
            HealthRecordSnapshot r, String today) {
        return RecordDisplayStatus.compute(list, pet, d(today), d("2026-01-01")).get(r.id());
    }

    @Test
    void ac4LatestF4OverdueAfterNextDateAndF3ClearsIt() {
        var f4 = vaccine(VaccinePreset.F4, null, "2026-11-01", "2026-12-01");
        assertThat(of(List.of(f4), CAT, f4, "2026-11-20").recordStatus()).isEqualTo(RecordStatus.UPCOMING);
        assertThat(of(List.of(f4), CAT, f4, "2026-11-24").recordStatus()).isEqualTo(RecordStatus.DUE_SOON);
        assertThat(of(List.of(f4), CAT, f4, "2026-12-01").recordStatus()).isEqualTo(RecordStatus.DUE_SOON);
        var overdue = of(List.of(f4), CAT, f4, "2026-12-02");
        assertThat(overdue.recordStatus()).isEqualTo(RecordStatus.OVERDUE);
        assertThat(overdue.displayNextDate()).isEqualTo(d("2026-12-01"));
        var f3 = vaccine(VaccinePreset.F3, null, "2026-12-05", null);
        assertThat(of(List.of(f4, f3), CAT, f4, "2026-12-06")).isEqualTo(RecordDisplayStatus.Display.DONE);
    }

    @Test
    void dueSoonBoundaryTodayPlusSeven() {
        var r = rec(HealthRecordType.CUSTOM, "2026-11-01", "2026-11-17");
        assertThat(of(List.of(r), CAT, r, "2026-11-10").recordStatus()).isEqualTo(RecordStatus.DUE_SOON);
        assertThat(of(List.of(r), CAT, r, "2026-11-09").recordStatus()).isEqualTo(RecordStatus.UPCOMING);
    }

    @Test
    void ac14VetVisitShowsUntilVisitOnOrAfterDMinus1() {
        var visit = rec(HealthRecordType.VET_VISIT, "2026-10-01", "2026-11-14");
        var early = rec(HealthRecordType.VET_VISIT, "2026-11-05", null);
        var d = of(List.of(visit, early), CAT, visit, "2026-11-10");
        assertThat(d.displayNextDate()).isEqualTo(d("2026-11-14"));
        assertThat(of(List.of(visit, early), CAT, visit, "2026-11-15").recordStatus())
                .isEqualTo(RecordStatus.OVERDUE);
        var onD1 = rec(HealthRecordType.VET_VISIT, "2026-11-13", null);
        assertThat(of(List.of(visit, early, onD1), CAT, visit, "2026-11-15").recordStatus())
                .isEqualTo(RecordStatus.DONE);
    }

    @Test
    void ac21CoveredInternalDoneAndComesBackWhenCoverRemoved() {
        var internal = deworm(DewormType.INTERNAL, "2027-01-01", "2027-04-01");
        var fw = deworm(DewormType.FLEA_WORM, "2027-02-01", "2027-03-01");
        assertThat(of(List.of(internal, fw), DOG_F, internal, "2027-02-10").recordStatus())
                .isEqualTo(RecordStatus.DONE);
        var back = of(List.of(internal), DOG_F, internal, "2027-02-10");
        assertThat(back.displayNextDate()).isEqualTo(d("2027-04-01"));
        var newer = deworm(DewormType.INTERNAL, "2027-02-15", "2027-05-15");
        var all = List.of(internal, fw, newer);
        assertThat(of(all, DOG_F, newer, "2027-02-16").displayNextDate()).isEqualTo(d("2027-05-15"));
        assertThat(of(all, DOG_F, fw, "2027-02-16").displayNextDate()).isEqualTo(d("2027-03-01"));
    }

    @Test
    void ac51Ac53BackfillBeforeLaunchIsSilent() {
        var f4 = backfill(vaccine(VaccinePreset.F4, null, "2026-10-28", "2026-11-28"));
        var map = RecordDisplayStatus.compute(List.of(f4), CAT, d("2026-12-02"), d("2026-12-01"));
        assertThat(map.get(f4.id())).isEqualTo(RecordDisplayStatus.Display.DONE);
        var legacy = backfill(deworm(null, "2026-03-01", "2026-06-01"));
        assertThat(RecordDisplayStatus.compute(List.of(legacy), DOG_F, d("2026-12-02"), d("2026-12-01"))
                .get(legacy.id())).isEqualTo(RecordDisplayStatus.Display.DONE);
        // 回填但 Next date 在上线后 → 正常显示（AC53 第一条：15 Jan 2027）
        var later = backfill(deworm(null, "2026-10-15", "2027-01-15"));
        assertThat(RecordDisplayStatus.compute(List.of(later), DOG_F, d("2026-12-02"), d("2026-12-01"))
                .get(later.id()).displayNextDate()).isEqualTo(d("2027-01-15"));
    }

    @Test
    void heatLaterRecordShowsEpisodeFirstNextLockedAndNeverOverdue() {
        var first = rec(HealthRecordType.MENSTRUATION, "2027-01-01", "2027-07-01");
        var later = rec(HealthRecordType.MENSTRUATION, "2027-01-06", null);
        var list = List.of(first, later);
        var dl = of(list, DOG_F, later, "2027-02-01");
        assertThat(dl.displayNextDate()).isEqualTo(d("2027-07-01"));
        assertThat(dl.nextDateLocked()).isTrue();
        assertThat(of(list, DOG_F, first, "2027-02-01").recordStatus()).isEqualTo(RecordStatus.DONE);
        assertThat(of(list, DOG_F, later, "2027-07-02").recordStatus()).isEqualTo(RecordStatus.DONE);
    }

    @Test
    void heatOnlyForIntactFemaleDog() {
        var heat = rec(HealthRecordType.MENSTRUATION, "2027-01-01", "2027-07-01");
        var spayed = pet(PetType.DOG, PetSex.FEMALE, d("2024-01-01"), NeuterStatus.NEUTERED);
        assertThat(of(List.of(heat), spayed, heat, "2027-02-01").recordStatus()).isEqualTo(RecordStatus.DONE);
        assertThat(of(List.of(heat), CAT, heat, "2027-02-01").recordStatus()).isEqualTo(RecordStatus.DONE);
    }

    @Test
    void noNextDateOrNeuterIsDone() {
        var neuter = rec(HealthRecordType.NEUTER, "2027-01-01", null);
        var custom = rec(HealthRecordType.CUSTOM, "2027-01-01", null);
        assertThat(of(List.of(neuter, custom), CAT, custom, "2027-02-01")).isEqualTo(RecordDisplayStatus.Display.DONE);
    }
}
