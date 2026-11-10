package com.tailtopia.admin.aiquery;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.aiquery.domain.AiQueryFailure;
import com.tailtopia.admin.aiquery.domain.AiQueryStatus;
import org.junit.jupiter.api.Test;

/** L0：失败分类（FR9）。 */
class AiQueryFailureTest {

    @Test
    void mapping() {
        assertThat(AiQueryFailure.of(AiQueryStatus.DONE, null)).isNull();
        assertThat(AiQueryFailure.of(AiQueryStatus.RUNNING, null)).isNull();
        assertThat(AiQueryFailure.of(AiQueryStatus.TIMEOUT, "TIMEOUT")).isEqualTo(AiQueryFailure.TIMEOUT);
        assertThat(AiQueryFailure.of(AiQueryStatus.FAILED, "AGENT_TIMEOUT")).isEqualTo(AiQueryFailure.TIMEOUT);
        assertThat(AiQueryFailure.of(AiQueryStatus.FAILED, "AGENT_RATE_LIMITED")).isEqualTo(AiQueryFailure.RATE_LIMITED);
        assertThat(AiQueryFailure.of(AiQueryStatus.FAILED, "AGENT_AUTH_FAILED")).isEqualTo(AiQueryFailure.AUTH_FAILED);
        assertThat(AiQueryFailure.of(AiQueryStatus.FAILED, "AGENT_LOST")).isEqualTo(AiQueryFailure.FAILED);
        assertThat(AiQueryFailure.of(AiQueryStatus.FAILED, null)).isEqualTo(AiQueryFailure.FAILED);
        assertThat(AiQueryFailure.of(AiQueryStatus.DONE, "AGENT_TIMEOUT")).as("未失败一律 null").isNull();
        assertThat(AiQueryFailure.AUTH_FAILED.retryable()).isFalse();
        assertThat(AiQueryFailure.TIMEOUT.warn()).isTrue();
        assertThat(AiQueryFailure.RATE_LIMITED.warn()).isTrue();
        assertThat(AiQueryFailure.FAILED.warn()).isFalse();
        assertThat(AiQueryFailure.AUTH_FAILED.warn()).isFalse();
    }
}
