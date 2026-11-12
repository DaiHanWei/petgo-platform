package com.tailtopia.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * L1：埋点库连不上时后端照常启动（analytics-pipeline Story 1.1 AC4）。
 *
 * <p>把埋点库指到一个不存在的库：上下文必须正常起来、{@code /actuator/health} 仍 UP（部署脚本靠它判活）、
 * 业务接口可用；管道停用、原因 {@code DB_UNAVAILABLE}、TECH 告警恰好一次。
 */
@TestPropertySource(properties =
        "analytics.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/petgo_analytics_absent")
class AnalyticsDbDownIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AnalyticsPipelineState state;
    @MockitoBean
    private OpsAlertService alerts;

    @Test
    void pipelineDisabledButBusinessUnaffected() throws Exception {
        assertThat(state.isEnabled()).isFalse();
        assertThat(state.reason()).contains(Reason.DB_UNAVAILABLE);
        verify(alerts, times(1)).send(eq(OpsChannel.TECH), startsWith("analytics-pipeline-disabled:"), anyString());

        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        // 任一现有业务接口（免鉴权、读业务库）照常可用
        mvc.perform(get("/api/v1/support/contact"))
                .andExpect(status().isOk());
    }
}
