package com.tailtopia.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import org.junit.jupiter.api.Test;

/** L0：管道停用状态（analytics-pipeline Story 1.1 AC4）。 */
class AnalyticsPipelineStateTest {

    @Test
    void enabledByDefault() {
        AnalyticsPipelineState s = new AnalyticsPipelineState();
        assertThat(s.isEnabled()).isTrue();
        assertThat(s.reason()).isEmpty();
        assertThat(s.detail()).isEmpty();
    }

    @Test
    void firstDisableWinsAndIsNotOverwritten() {
        AnalyticsPipelineState s = new AnalyticsPipelineState();
        assertThat(s.disable(Reason.DB_UNAVAILABLE, "a")).isTrue();
        assertThat(s.disable(Reason.MIGRATION_FAILED, "b")).isFalse();
        assertThat(s.isEnabled()).isFalse();
        assertThat(s.reason()).contains(Reason.DB_UNAVAILABLE);
        assertThat(s.detail()).contains("a");
    }

    @Test
    void reservedCatalogInvalidReason() {
        assertThat(Reason.valueOf("CATALOG_INVALID")).isNotNull();
    }

    @Test
    void nullReasonRejected() {
        assertThatThrownBy(() -> new AnalyticsPipelineState().disable(null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
