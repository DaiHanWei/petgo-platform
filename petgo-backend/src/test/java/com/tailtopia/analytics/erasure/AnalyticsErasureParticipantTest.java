package com.tailtopia.analytics.erasure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** L0：注销擦除参与者（analytics-pipeline Story 1.8 AC1）—— 写业务库 outbox，user_key 与 AnalyticsDistinctId 一致，失败不吞。 */
class AnalyticsErasureParticipantTest {

    private final JdbcTemplate business = mock(JdbcTemplate.class);
    private final AnalyticsErasureParticipant participant = new AnalyticsErasureParticipant(business);

    @Test
    void registersOutboxRowWithDistinctIdAsUserKey() {
        participant.register(42L);

        verify(business).update(AnalyticsErasureParticipant.INSERT_SQL, 42L, AnalyticsDistinctId.of(42L));
    }

    @Test
    void insertIsIdempotentOnUserId() {
        // 状态机重跑：同一用户只一行
        org.assertj.core.api.Assertions.assertThat(AnalyticsErasureParticipant.INSERT_SQL)
                .contains("analytics_erasure_outbox")
                .contains("ON CONFLICT (user_id) DO NOTHING");
    }

    @Test
    void writeFailurePropagates() {
        when(business.update(anyString(), eq(7L), eq(AnalyticsDistinctId.of(7L))))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> participant.register(7L)).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
