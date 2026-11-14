package com.tailtopia.notify.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.notify.schedule.AnniversaryPlan.AnniversaryPush;
import com.tailtopia.profile.dto.PetProfileSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：陪伴纪念日计划纯函数（V1.5.0 onboarding-recall Story 5.2 · AC3 / AC4 · FR-182）—— 节点、只读剔除、主人无效剔除、
 * 同主人同天只推最早一只（created_at, id）、WIB 日期边界。
 */
class AnniversaryPlanTest {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    static PetProfileSnapshot pet(long id, long owner, Instant createdAt, String status, boolean ownerActive) {
        return new PetProfileSnapshot(id, owner, "Pet" + id, null,
                createdAt.atZone(java.time.ZoneOffset.UTC).toLocalDate(), createdAt.atZone(WIB).toLocalDate(),
                status, ownerActive, createdAt);
    }

    /** 建档于 TODAY − days（WIB 中午）。 */
    static Instant daysAgo(int days, int extraSeconds) {
        return TODAY.minusDays(days).atTime(12, 0).atZone(WIB).toInstant().plusSeconds(extraSeconds);
    }

    @Test
    @DisplayName("当天满 30 / 100 / 365 天 → 计划；29 / 31 / 101 天不计划")
    void nodes() {
        List<PetProfileSnapshot> pets = List.of(
                pet(1, 10, daysAgo(30, 0), "ACTIVE", true),
                pet(2, 20, daysAgo(100, 0), "ACTIVE", true),
                pet(3, 30, daysAgo(365, 0), "ACTIVE", true),
                pet(4, 40, daysAgo(29, 0), "ACTIVE", true),
                pet(5, 50, daysAgo(31, 0), "ACTIVE", true),
                pet(6, 60, daysAgo(101, 0), "ACTIVE", true));
        assertThat(AnniversaryPlan.compute(pets, TODAY)).extracting(AnniversaryPush::petId, AnniversaryPush::days,
                AnniversaryPush::push).containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, 30, true),
                        org.assertj.core.groups.Tuple.tuple(2L, 100, true),
                        org.assertj.core.groups.Tuple.tuple(3L, 365, true));
    }

    @Test
    @DisplayName("AC3：只读宠物、主人非 active → 剔除（不推也不写通知）")
    void readOnlyAndInactiveOwnerExcluded() {
        List<PetProfileSnapshot> pets = List.of(
                pet(1, 10, daysAgo(30, 0), "READ_ONLY", true),
                pet(2, 20, daysAgo(30, 0), "ACTIVE", false));
        assertThat(AnniversaryPlan.compute(pets, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("AC4：同主人同天两只 → 建档最早那只 push=true，另一只 push=false；输出顺序同入参")
    void sameOwnerEarliestPushes() {
        // Kopi 建档更早（同为 30 天，但早 1 小时）；入参故意把 Mochi 放前面
        PetProfileSnapshot mochi = pet(5, 10, daysAgo(30, 3600), "ACTIVE", true);
        PetProfileSnapshot kopi = pet(7, 10, daysAgo(30, 0), "ACTIVE", true);
        List<AnniversaryPush> out = AnniversaryPlan.compute(List.of(mochi, kopi), TODAY);
        assertThat(out).extracting(AnniversaryPush::petId, AnniversaryPush::push).containsExactly(
                org.assertj.core.groups.Tuple.tuple(5L, false), org.assertj.core.groups.Tuple.tuple(7L, true));
    }

    @Test
    @DisplayName("AC4：同主人不同节点（30 与 100 同天）也只推最早那只")
    void sameOwnerDifferentNodes() {
        PetProfileSnapshot old = pet(1, 10, daysAgo(100, 0), "ACTIVE", true);
        PetProfileSnapshot young = pet(2, 10, daysAgo(30, 0), "ACTIVE", true);
        assertThat(AnniversaryPlan.compute(List.of(young, old), TODAY)).extracting(AnniversaryPush::petId,
                AnniversaryPush::push).containsExactly(org.assertj.core.groups.Tuple.tuple(2L, false),
                        org.assertj.core.groups.Tuple.tuple(1L, true));
    }

    @Test
    @DisplayName("同建档时刻 → id 小者最早；只读那只不参与「最早」竞争")
    void tieBreakByIdAndReadOnlyDoesNotCompete() {
        Instant t = daysAgo(30, 0);
        assertThat(AnniversaryPlan.compute(List.of(pet(9, 10, t, "ACTIVE", true), pet(8, 10, t, "ACTIVE", true)),
                TODAY)).filteredOn(AnniversaryPush::push).extracting(AnniversaryPush::petId).containsExactly(8L);

        PetProfileSnapshot earliestButReadOnly = pet(1, 10, daysAgo(30, 0), "READ_ONLY", true);
        PetProfileSnapshot active = pet(2, 10, daysAgo(30, 60), "ACTIVE", true);
        assertThat(AnniversaryPlan.compute(List.of(earliestButReadOnly, active), TODAY))
                .extracting(AnniversaryPush::petId, AnniversaryPush::push)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2L, true));
    }

    @Test
    @DisplayName("WIB 边界：UTC 前一天 20:00 建档 = WIB 次日 03:00 → 按 WIB 日计天数")
    void wibDateBoundary() {
        // UTC 10-15 20:00 = WIB 10-16 03:00；TODAY 11-15 距 WIB 10-16 正好 30 天（距 UTC 10-15 是 31 天）
        Instant created = Instant.parse("2026-10-15T20:00:00Z");
        PetProfileSnapshot p = pet(1, 10, created, "ACTIVE", true);
        assertThat(p.createdDateWib()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(AnniversaryPlan.compute(List.of(p), TODAY)).singleElement()
                .satisfies(a -> assertThat(a.days()).isEqualTo(30));
        assertThat(AnniversaryPlan.compute(List.of(p), TODAY.plusDays(1))).isEmpty();
    }

    @Test
    @DisplayName("5 参兼容构造：WIB 日 = createdDate、ACTIVE、主人有效、createdAt 缺失按日期 + id 比")
    void legacyConstructorDefaults() {
        PetProfileSnapshot a = new PetProfileSnapshot(3, 10, "A", null, TODAY.minusDays(30));
        PetProfileSnapshot b = new PetProfileSnapshot(2, 10, "B", null, TODAY.minusDays(30));
        assertThat(AnniversaryPlan.compute(List.of(a, b), TODAY)).filteredOn(AnniversaryPush::push)
                .extracting(AnniversaryPush::petId).containsExactly(2L);
    }
}
