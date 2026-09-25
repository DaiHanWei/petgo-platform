package com.tailtopia.admin.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** L0：看板 18 项指标枚举（V1.3.0 Story 3.2 AC2）：恰 18 值、顺序 = 口径表 #、双口径集合 = {3,4,8..14}、hint key 形状。 */
class DashboardMetricTest {

    @Test
    void exactlyEighteenInPrdOrder() {
        assertThat(DashboardMetric.values()).hasSize(18);
        assertThat(DashboardMetric.keys()).containsExactly(
                "new_users", "cumulative_users", "posting_users", "new_posts", "new_pet_owners", "cumulative_pet_owners",
                "diary_pet_owners", "interacted_posts", "silent_posts", "engagement_score", "new_posts_score",
                "all_posts_avg_score", "interacted_posts_avg_score", "engagement_score_all_time", "paying_users_cash",
                "payments_cash", "paying_users_incl_pawcoin", "payments_incl_pawcoin");
        for (int i = 0; i < DashboardMetric.values().length; i++) {
            assertThat(DashboardMetric.values()[i].number()).isEqualTo(i + 1);
        }
    }

    @Test
    void dualScopeIsExactlyTheNinePostMetrics() {
        Set<Integer> dual = Arrays.stream(DashboardMetric.values()).filter(DashboardMetric::dualScope)
                .map(DashboardMetric::number).collect(Collectors.toSet());
        assertThat(dual).containsExactlyInAnyOrder(3, 4, 8, 9, 10, 11, 12, 13, 14);
        assertThat(DashboardMetric.NEW_POSTS.scopes()).containsExactlyInAnyOrder(MetricScope.ALL, MetricScope.REAL);
        assertThat(DashboardMetric.NEW_USERS.scopes()).containsExactly(MetricScope.ALL);
    }

    @Test
    void hintKeyAndRoundTrip() {
        assertThat(DashboardMetric.CUMULATIVE_USERS.hintKey()).isEqualTo("admin.v130.dashboard.metric.cumulative_users.hint");
        for (DashboardMetric m : DashboardMetric.values()) {
            assertThat(DashboardMetric.fromKey(m.key())).isSameAs(m);
            assertThat(m.key()).matches("[a-z_]+");
        }
        assertThatThrownBy(() -> DashboardMetric.fromKey("total_installed_users")).isInstanceOf(IllegalArgumentException.class);
        List<String> keys = DashboardMetric.keys();
        assertThat(keys).doesNotHaveDuplicates().allSatisfy(k -> assertThat(k.length()).isLessThanOrEqualTo(48));
    }
}
