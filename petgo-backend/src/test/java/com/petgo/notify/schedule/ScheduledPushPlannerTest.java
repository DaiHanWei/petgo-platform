package com.petgo.notify.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.petgo.notify.domain.NotificationType;
import com.petgo.profile.dto.PetProfileSnapshot;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Story 6.7 J5 金标单测：定时推送计划器纯逻辑（注入当前日期 + 假快照 + 去重集）。
 * 覆盖生日（含 2/29 兜底、第一个生日里程碑、按年去重）、纪念日（30/100/365 + 边界 + 去重）、范围守护。
 */
class ScheduledPushPlannerTest {

    private final ScheduledPushPlanner planner = new ScheduledPushPlanner();

    private static PetProfileSnapshot pet(long id, String name, LocalDate birthday, LocalDate created) {
        return new PetProfileSnapshot(id, id * 10, name, birthday, created);
    }

    @Test
    void birthday_tomorrow_plans_pet_birthday_with_age() {
        LocalDate today = LocalDate.of(2026, 6, 8);
        var p = pet(1, "Momo", LocalDate.of(2020, 6, 9), LocalDate.of(2026, 1, 1));
        List<PlannedPush> out = planner.plan(today, List.of(p), Set.of());
        assertThat(out).hasSize(1);
        assertThat(out.get(0).type()).isEqualTo(NotificationType.PET_BIRTHDAY);
        assertThat(out.get(0).nodeKey()).isEqualTo("2026"); // 按年份去重
        assertThat(out.get(0).number()).isEqualTo(6); // 岁数
        assertThat(out.get(0).ownerId()).isEqualTo(10);
    }

    @Test
    void birthday_null_or_not_tomorrow_skipped() {
        LocalDate today = LocalDate.of(2026, 6, 8);
        var noBirthday = pet(1, "A", null, LocalDate.of(2026, 1, 1));
        var notTomorrow = pet(2, "B", LocalDate.of(2020, 12, 25), LocalDate.of(2026, 1, 1));
        assertThat(planner.plan(today, List.of(noBirthday, notTomorrow), Set.of())).isEmpty();
    }

    @Test
    void birthday_deduped_when_year_already_pushed() {
        LocalDate today = LocalDate.of(2026, 6, 8);
        var p = pet(1, "Momo", LocalDate.of(2020, 6, 9), LocalDate.of(2026, 1, 1));
        Set<String> existing = Set.of("1|PET_BIRTHDAY|2026");
        assertThat(planner.plan(today, List.of(p), existing)).isEmpty();
    }

    @Test
    void first_birthday_also_plans_milestone_node() {
        LocalDate today = LocalDate.of(2026, 6, 8);
        var p = pet(1, "Momo", LocalDate.of(2025, 6, 9), LocalDate.of(2025, 6, 9));
        List<PlannedPush> out = planner.plan(today, List.of(p), Set.of());
        assertThat(out).extracting(PlannedPush::type)
                .containsExactlyInAnyOrder(NotificationType.PET_BIRTHDAY, NotificationType.MILESTONE_NODE);
        assertThat(out).filteredOn(x -> x.type() == NotificationType.MILESTONE_NODE)
                .singleElement()
                .satisfies(x -> assertThat(x.nodeKey()).isEqualTo(ScheduledPushPlanner.FIRST_BIRTHDAY_NODE));
    }

    @Test
    void age_over_one_no_milestone_node() {
        LocalDate today = LocalDate.of(2026, 6, 8);
        var p = pet(1, "Momo", LocalDate.of(2020, 6, 9), LocalDate.of(2026, 1, 1));
        assertThat(planner.plan(today, List.of(p), Set.of()))
                .noneMatch(x -> x.type() == NotificationType.MILESTONE_NODE);
    }

    @Test
    void leap_day_birthday_falls_back_to_feb28_in_non_leap_year() {
        // 2027 平年：闰日生日在 2/28 纪念（前 1 天 = today 2/27）。
        LocalDate today = LocalDate.of(2027, 2, 27);
        var p = pet(1, "Leap", LocalDate.of(2024, 2, 29), LocalDate.of(2026, 1, 1));
        List<PlannedPush> out = planner.plan(today, List.of(p), Set.of());
        assertThat(out).singleElement()
                .satisfies(x -> assertThat(x.type()).isEqualTo(NotificationType.PET_BIRTHDAY));
    }

    @Test
    void leap_day_birthday_direct_match_in_leap_year() {
        // 2028 闰年：闰日生日 2/29 直接匹配（前 1 天 = today 2/28）。
        LocalDate today = LocalDate.of(2028, 2, 28);
        var p = pet(1, "Leap", LocalDate.of(2024, 2, 29), LocalDate.of(2026, 1, 1));
        assertThat(planner.plan(today, List.of(p), Set.of())).hasSize(1);
    }

    @Test
    void anniversary_nodes_plan_on_exact_days() {
        for (int node : List.of(30, 100, 365)) {
            LocalDate today = LocalDate.of(2026, 6, 8);
            var p = pet(1, "Momo", null, today.minusDays(node));
            List<PlannedPush> out = planner.plan(today, List.of(p), Set.of());
            assertThat(out).singleElement().satisfies(x -> {
                assertThat(x.type()).isEqualTo(NotificationType.COMPANION_ANNIVERSARY);
                assertThat(x.nodeKey()).isEqualTo(String.valueOf(node));
                assertThat(x.number()).isEqualTo(node);
            });
        }
    }

    @Test
    void anniversary_boundaries_not_triggered() {
        LocalDate today = LocalDate.of(2026, 6, 8);
        for (int days : List.of(29, 31, 99, 101, 364, 366)) {
            var p = pet(1, "Momo", null, today.minusDays(days));
            assertThat(planner.plan(today, List.of(p), Set.of()))
                    .as("days=" + days).isEmpty();
        }
    }

    @Test
    void anniversary_deduped_when_node_already_pushed() {
        LocalDate today = LocalDate.of(2026, 6, 8);
        var p = pet(1, "Momo", null, today.minusDays(100));
        Set<String> existing = Set.of("1|COMPANION_ANNIVERSARY|100");
        assertThat(planner.plan(today, List.of(p), existing)).isEmpty();
    }

    @Test
    void planner_only_emits_three_push_types_no_milestone_body_data() {
        // 范围守护：计划器为纯逻辑，仅产出推送意图（三类 type），绝不含里程碑完成/徽章数据。
        LocalDate today = LocalDate.of(2026, 6, 8);
        var birthday = pet(1, "A", LocalDate.of(2025, 6, 9), today.minusDays(30));
        List<PlannedPush> out = planner.plan(today, List.of(birthday), Set.of());
        assertThat(out).extracting(PlannedPush::type)
                .allMatch(t -> t == NotificationType.PET_BIRTHDAY
                        || t == NotificationType.COMPANION_ANNIVERSARY
                        || t == NotificationType.MILESTONE_NODE);
    }
}
