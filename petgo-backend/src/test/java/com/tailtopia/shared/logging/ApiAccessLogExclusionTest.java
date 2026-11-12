package com.tailtopia.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** L0：analytics-pipeline Story 1.4 AC9 —— 埋点批量接口不进全接口访问日志，其他 {@code /api/} 照常记录。 */
class ApiAccessLogExclusionTest {

    private final ApiAccessLoggingFilter filter = new ApiAccessLoggingFilter(new LogSanitizer());

    private boolean skipped(String uri) {
        return filter.shouldNotFilter(new MockHttpServletRequest("POST", uri));
    }

    @Test
    @DisplayName("/api/v1/analytics/batch（含尾斜杠）不记录")
    void analyticsBatchExcluded() {
        assertThat(skipped("/api/v1/analytics/batch")).isTrue();
        assertThat(skipped("/api/v1/analytics/batch/")).isTrue();
    }

    @Test
    @DisplayName("其他 /api/ 与 /im/callback 照常记录；非 API 路径不记录")
    void othersUnchanged() {
        assertThat(skipped("/api/v1/me")).isFalse();
        assertThat(skipped("/api/v1/analytics")).isFalse();
        assertThat(skipped("/api/v1/analytics/batchx")).isFalse();
        assertThat(skipped("/im/callback")).isFalse();
        assertThat(skipped("/actuator/health")).isTrue();
    }
}
