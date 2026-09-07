package com.tailtopia.notify.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.dto.UserLifecycleSnapshot;
import com.tailtopia.notify.domain.NotificationType;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 留存运营作战手册 · 抓手 1 金标单测：生命周期推送计划器纯逻辑
 * （注入当天 + 假用户快照 + 宠物名映射）。
 *
 * <p>覆盖：四节点触发窗口、分层选择（未建档/已建档未发布/已发布）、
 * 一人一天至多一条、召回按月去重与「已过 D7 才算流失」、投递优先级排序。
 */
class LifecyclePushPlannerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 21);
    private static final int WINBACK_DAYS = 7;

    private final LifecyclePushPlanner planner = new LifecyclePushPlanner();

    /** 注册于 {@code TODAY - ageDays}，最后活跃于 {@code TODAY - inactiveDays}（null=从未记录）。 */
    private static UserLifecycleSnapshot user(long id, int ageDays, Integer inactiveDays, int published) {
        return new UserLifecycleSnapshot(id, TODAY.minusDays(ageDays),
                inactiveDays == null ? null : TODAY.minusDays(inactiveDays), published);
    }

    private static Map<Long, String> withPet(long id, String name) {
        return Map.of(id, name);
    }

    private List<LifecyclePlannedPush> plan(List<UserLifecycleSnapshot> users, Map<Long, String> pets) {
        return planner.plan(TODAY, users, pets, WINBACK_DAYS);
    }

    // ===================== D1：手册的第一武器 =====================

    @Test
    void d1_with_profile_asks_to_record_that_pet_by_name() {
        var out = plan(List.of(user(1, 1, 0, 0)), withPet(1, "Mochi"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).type()).isEqualTo(NotificationType.LIFECYCLE_D1);
        assertThat(out.get(0).variant()).isEqualTo(LifecycleVariant.RECORD);
        // 铁律：文案必须带宠物名——planner 必须把名字带下去，否则 dispatcher 无从渲染。
        assertThat(out.get(0).petName()).isEqualTo("Mochi");
        assertThat(out.get(0).copyKey()).isEqualTo("LIFECYCLE_D1.RECORD");
        assertThat(out.get(0).nodeKey()).isEqualTo(LifecyclePlannedPush.ONCE);
    }

    @Test
    void d1_without_profile_goes_to_create_profile_and_carries_no_pet_name() {
        var out = plan(List.of(user(1, 1, 0, 0)), Map.of());
        assertThat(out).hasSize(1);
        assertThat(out.get(0).variant()).isEqualTo(LifecycleVariant.CREATE_PROFILE);
        assertThat(out.get(0).petName()).isNull(); // 没建档就没有名字可说
    }

    @Test
    void node_window_tolerates_one_missed_scan_but_not_a_stale_backlog() {
        // 第 2 天补发（日扫漏跑一次的补偿窗口）。
        assertThat(plan(List.of(user(1, 2, 0, 0)), withPet(1, "Mochi")))
                .singleElement()
                .extracting(LifecyclePlannedPush::type)
                .isEqualTo(NotificationType.LIFECYCLE_D1);
        // 第 3 天已出窗——绝不能给两个月前注册的老用户补一条「你昨天注册了」。
        assertThat(plan(List.of(user(1, 3, 0, 5)), withPet(1, "Mochi")))
                .noneMatch(p -> p.type() == NotificationType.LIFECYCLE_D1);
    }

    @Test
    void registered_today_gets_nothing() {
        assertThat(plan(List.of(user(1, 0, 0, 0)), withPet(1, "Mochi"))).isEmpty();
    }

    // ===================== D3：内容钩子，只给还没发布的人 =====================

    @Test
    void d3_unpublished_with_profile_gets_feed_hook() {
        var out = plan(List.of(user(1, 3, 0, 0)), withPet(1, "Mochi"));
        assertThat(out).singleElement().satisfies(p -> {
            assertThat(p.type()).isEqualTo(NotificationType.LIFECYCLE_D3);
            assertThat(p.variant()).isEqualTo(LifecycleVariant.FEED);
        });
    }

    @Test
    void d3_skipped_for_already_published_user() {
        // 已发布 = 已经完成我们想要的动作，不该再被内容钩子打扰。
        assertThat(plan(List.of(user(1, 3, 0, 2)), withPet(1, "Mochi"))).isEmpty();
    }

    // ===================== D7：周回顾 / 兜底引导 =====================

    @Test
    void d7_published_gets_weekly_review() {
        var out = plan(List.of(user(1, 7, 0, 3)), withPet(1, "Mochi"));
        assertThat(out).singleElement().satisfies(p -> {
            assertThat(p.type()).isEqualTo(NotificationType.LIFECYCLE_D7);
            assertThat(p.variant()).isEqualTo(LifecycleVariant.REVIEW);
            assertThat(p.petName()).isEqualTo("Mochi");
        });
    }

    @Test
    void d7_profile_but_never_published_gets_record_nudge() {
        assertThat(plan(List.of(user(1, 7, 0, 0)), withPet(1, "Mochi")))
                .singleElement()
                .extracting(LifecyclePlannedPush::variant)
                .isEqualTo(LifecycleVariant.RECORD);
    }

    @Test
    void d7_without_profile_still_goes_to_create_profile() {
        assertThat(plan(List.of(user(1, 7, 0, 0)), Map.of()))
                .singleElement()
                .extracting(LifecyclePlannedPush::variant)
                .isEqualTo(LifecycleVariant.CREATE_PROFILE);
    }

    // ===================== 流失召回 =====================

    @Test
    void winback_fires_for_lapsed_user_past_the_d7_window_and_dedupes_by_month() {
        var out = plan(List.of(user(1, 30, 10, 0)), Map.of());
        assertThat(out).singleElement().satisfies(p -> {
            assertThat(p.type()).isEqualTo(NotificationType.LIFECYCLE_WINBACK);
            // 手册：ROI 最高的一刀是把「装了没建档」的人直接送到建档页。
            assertThat(p.variant()).isEqualTo(LifecycleVariant.CREATE_PROFILE);
            assertThat(p.nodeKey()).isEqualTo("2026-08"); // 每月至多一次
        });
    }

    @Test
    void winback_skipped_for_recently_active_user() {
        assertThat(plan(List.of(user(1, 30, 2, 4)), withPet(1, "Mochi"))).isEmpty();
    }

    @Test
    void winback_never_competes_with_the_d1_to_d7_nodes() {
        // 注册 7 天、7 天没回来——同时满足 D7 与「流失」。必须只出一条，且是 D7。
        var out = plan(List.of(user(1, 7, 7, 0)), withPet(1, "Mochi"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).type()).isEqualTo(NotificationType.LIFECYCLE_D7);
    }

    @Test
    void missing_last_active_falls_back_to_registration_date() {
        // 存量数据回填前 last_active_at 可能为空。宁可误判为流失（代价一条召回），
        // 也不要因为缺一个字段就把手册最想捞回来的那 506 人永久排除在外。
        assertThat(plan(List.of(user(1, 40, null, 0)), Map.of()))
                .singleElement()
                .extracting(LifecyclePlannedPush::type)
                .isEqualTo(NotificationType.LIFECYCLE_WINBACK);
    }

    // ===================== 全局不变量 =====================

    @Test
    void output_is_ordered_by_urgency_so_the_daily_cap_cuts_winback_first() {
        var users = List.of(
                user(1, 30, 20, 0),  // 召回
                user(2, 7, 0, 1),    // D7
                user(3, 1, 0, 0),    // D1
                user(4, 3, 0, 0));   // D3
        var out = plan(users, Map.of(2L, "B", 3L, "C", 4L, "D"));
        assertThat(out).extracting(LifecyclePlannedPush::type).containsExactly(
                NotificationType.LIFECYCLE_D1,
                NotificationType.LIFECYCLE_D3,
                NotificationType.LIFECYCLE_D7,
                NotificationType.LIFECYCLE_WINBACK);
    }

    @Test
    void at_most_one_push_per_user_per_day() {
        // 每个用户都只出一条：同一天既收「记录 Mochi」又收「回来看看」，
        // 用户学到的不是「该记录了」，而是「这个 App 很吵」。
        var users = List.of(user(1, 1, 0, 0), user(2, 3, 0, 0), user(3, 7, 0, 0), user(4, 60, 30, 0));
        var out = plan(users, Map.of(1L, "A", 2L, "B", 3L, "C", 4L, "D"));
        assertThat(out).extracting(LifecyclePlannedPush::userId)
                .doesNotHaveDuplicates()
                .hasSize(4);
    }

    @Test
    void garbage_dates_are_skipped_not_turned_into_negative_ages() {
        var noRegisterDate = new UserLifecycleSnapshot(1, null, TODAY, 0);
        var futureRegisterDate = new UserLifecycleSnapshot(2, TODAY.plusDays(3), TODAY, 0);
        assertThat(plan(List.of(noRegisterDate, futureRegisterDate), Map.of())).isEmpty();
    }
}
