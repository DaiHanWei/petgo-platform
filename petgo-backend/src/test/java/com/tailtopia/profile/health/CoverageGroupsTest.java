package com.tailtopia.profile.health;

import static com.tailtopia.profile.health.HealthFixtures.deworm;
import static com.tailtopia.profile.health.HealthFixtures.rec;
import static com.tailtopia.profile.health.HealthFixtures.vaccine;
import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.VaccinePreset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0（V1.5.0 Story 1.2 · AC5）：覆盖组、单向覆盖、成对、Legacy、Vet visit 满足。 */
class CoverageGroupsTest {

    @Test
    void f3AndF4ShareAGroupAndOtherNameEqualToLabelIsThatPreset() {
        var f4 = vaccine(VaccinePreset.F4, "F4 (Tetracat)", "2026-10-01", "2026-12-01");
        var f3 = vaccine(VaccinePreset.F3, "F3 (Tricat)", "2026-12-05", null);
        var other = vaccine(VaccinePreset.OTHER, "  rabies ", "2026-10-01", null);
        var g = CoverageGroups.of(List.of(f4, f3, other), PetType.CAT);
        assertThat(g.groupOf(f4)).isEqualTo(g.groupOf(f3));
        assertThat(g.isLatest(f4)).isFalse();
        assertThat(RecordKinds.kindOf(other, PetType.CAT)).isEqualTo("VACCINE:RABIES");
    }

    @Test
    void otherNamesNormalisedAndCustomByName() {
        var a = vaccine(VaccinePreset.OTHER, "Vaksin  X", "2026-01-01", null);
        var b = vaccine(null, " vaksin x", "2026-02-01", null);
        assertThat(RecordKinds.kindOf(a, PetType.DOG)).isEqualTo(RecordKinds.kindOf(b, PetType.DOG));
        var vv1 = rec(HealthRecordType.VET_VISIT, "2026-01-01", null);
        var vv2 = rec(HealthRecordType.VET_VISIT, "2026-01-01", null);
        assertThat(RecordKinds.groupOf(vv1, PetType.CAT)).isNotEqualTo(RecordKinds.groupOf(vv2, PetType.CAT));
    }

    @Test
    void comboCoversPuppyDpOneWay() {
        var dp = vaccine(VaccinePreset.PUPPY_DP, null, "2027-03-01", "2027-03-22");
        var combo = vaccine(VaccinePreset.COMBO_DHPPI_L, null, "2027-03-20", "2027-04-17");
        var g = CoverageGroups.of(List.of(dp, combo), PetType.DOG);
        assertThat(g.isCovered(g.groupOf(dp))).isTrue();
        assertThat(g.isCovered(g.groupOf(combo))).isFalse();
    }

    @Test
    void ac21FleaWormCoversInternalUntilNewInternal() {
        var internal = deworm(DewormType.INTERNAL, "2027-01-01", "2027-04-01");
        var fleaWorm = deworm(DewormType.FLEA_WORM, "2027-02-01", "2027-03-01");
        var g = CoverageGroups.of(List.of(internal, fleaWorm), PetType.DOG);
        assertThat(g.coveredBy(g.groupOf(internal))).contains(fleaWorm);
        // 15 Feb 新 Internal → 新周期，不再被覆盖；Flea+worm 不受影响（单向）
        var newer = deworm(DewormType.INTERNAL, "2027-02-15", "2027-05-15");
        var g2 = CoverageGroups.of(List.of(internal, fleaWorm, newer), PetType.DOG);
        assertThat(g2.isCovered(g2.groupOf(newer))).isFalse();
        assertThat(g2.isCovered(g2.groupOf(fleaWorm))).isFalse();
    }

    @Test
    void internalPlusExternalWithinThreeDaysCoverFleaWorm() {
        var fw = deworm(DewormType.FLEA_WORM, "2027-01-01", "2027-02-01");
        var i = deworm(DewormType.INTERNAL, "2027-01-10", null);
        var e = deworm(DewormType.EXTERNAL, "2027-01-13", null);
        var g = CoverageGroups.of(List.of(fw, i, e), PetType.DOG);
        assertThat(g.coveredBy(g.groupOf(fw))).contains(e); // 对的日期 = 较晚者
        var e4 = deworm(DewormType.EXTERNAL, "2027-01-14", null);
        var g2 = CoverageGroups.of(List.of(fw, i, e4), PetType.DOG);
        assertThat(g2.isCovered(g2.groupOf(fw))).isFalse();
    }

    @Test
    void anyTypedDewormCoversLegacy() {
        var legacy = deworm(null, "2026-10-15", "2027-01-15");
        var typed = deworm(DewormType.HEARTWORM, "2026-12-01", "2027-01-01");
        var g = CoverageGroups.of(List.of(legacy, typed), PetType.DOG);
        assertThat(g.isCovered(RecordKinds.LEGACY_DEWORM)).isTrue();
    }

    @Test
    void ac14VetVisitSatisfiedOnlyByVisitOnOrAfterDMinus1() {
        var visit = rec(HealthRecordType.VET_VISIT, "2026-10-01", "2026-11-14");
        var early = rec(HealthRecordType.VET_VISIT, "2026-11-05", null);
        assertThat(CoverageGroups.of(List.of(visit, early), PetType.CAT).vetVisitSatisfiedBy(visit)).isEmpty();
        var onD1 = rec(HealthRecordType.VET_VISIT, "2026-11-13", null);
        assertThat(CoverageGroups.of(List.of(visit, early, onD1), PetType.CAT).vetVisitSatisfiedBy(visit))
                .contains(onD1);
    }

    @Test
    void sameDayTrioHasNoCoverageCycleLatestEntryWins() {
        // 同日：INTERNAL、EXTERNAL 先录，FLEA_WORM 最后录 → FW 覆盖两者，成对不反过来覆盖 FW。
        var i = deworm(DewormType.INTERNAL, "2026-11-01", "2027-02-01");
        var e = deworm(DewormType.EXTERNAL, "2026-11-01", "2026-12-01");
        var fw = deworm(DewormType.FLEA_WORM, "2026-11-01", "2026-12-01");
        var g = CoverageGroups.of(List.of(i, e, fw), PetType.DOG);
        assertThat(g.isCovered(g.groupOf(i))).isTrue();
        assertThat(g.isCovered(g.groupOf(e))).isTrue();
        assertThat(g.isCovered(g.groupOf(fw))).isFalse();
    }
}
