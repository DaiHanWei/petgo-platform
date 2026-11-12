package com.tailtopia.analytics.catalog;

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
 * L1：事件清单 yaml 坏掉时后端照常启动（analytics-pipeline Story 1.2 AC5）。
 *
 * <p>前置：埋点测试库可用（否则管道先以 {@code DB_UNAVAILABLE} 停用，原因不会是 {@code CATALOG_INVALID}）。
 * 上下文正常起来、{@code /actuator/health} 仍 UP；管道停用、原因 {@code CATALOG_INVALID}；清单告警恰好一次。
 */
@TestPropertySource(properties =
        "analytics.catalog.event-catalog-location=classpath:analytics/bad-syntax-event-catalog.yaml")
class EventCatalogInvalidIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    private EventCatalog catalog;
    @MockitoBean
    private OpsAlertService alerts;

    @Test
    void contextStartsPipelineDisabledAlertedOnce() throws Exception {
        assertThat(catalog.isLoaded()).isFalse();
        assertThat(state.isEnabled()).isFalse();
        assertThat(state.reason()).contains(Reason.CATALOG_INVALID);
        assertThat(state.detail()).hasValueSatisfying(d -> assertThat(d).containsPattern("第 \\d+ 行"));
        verify(alerts, times(1)).send(eq(OpsChannel.TECH), startsWith("analytics-catalog-invalid:"), anyString());

        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
