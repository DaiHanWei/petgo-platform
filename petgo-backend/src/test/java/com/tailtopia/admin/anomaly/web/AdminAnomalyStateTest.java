package com.tailtopia.admin.anomaly.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.anomaly.domain.AnomalyStatus;
import org.junit.jupiter.api.Test;

/** L0：两态页签参数解析（V1.3.0 Story 2.6 AC1）：{@code state} 优先，旧 {@code ?status=} 兼容，非法值 / all → OPEN。 */
class AdminAnomalyStateTest {

    @Test
    void stateParamCompat() {
        assertThat(AdminAnomalyController.resolveState(null, null)).isEqualTo(AnomalyStatus.OPEN);
        assertThat(AdminAnomalyController.resolveState("resolved", "OPEN")).isEqualTo(AnomalyStatus.RESOLVED);
        assertThat(AdminAnomalyController.resolveState(null, "RESOLVED")).isEqualTo(AnomalyStatus.RESOLVED);
        assertThat(AdminAnomalyController.resolveState(null, "open")).isEqualTo(AnomalyStatus.OPEN);
        assertThat(AdminAnomalyController.resolveState(null, "all")).isEqualTo(AnomalyStatus.OPEN);
        assertThat(AdminAnomalyController.resolveState("bogus", null)).isEqualTo(AnomalyStatus.OPEN);
    }
}
