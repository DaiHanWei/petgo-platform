package com.tailtopia.consult.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.consult.domain.ConsultRequestState;
import com.tailtopia.consult.service.ConsultRequestService;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * L0（无 DB/Redis）。Story 3.3 接单响应契约 + 支付窗常量。
 *
 * <p>契约字段 {@code requestToken}/{@code state}/{@code payDeadlineAt}（服务端权威时间，兽医侧倒计时 FR-53A）；
 * state 由枚举 {@code name()} 落 UPPER_SNAKE；支付窗常量 90s（1.5min）不信客户端计时。
 */
class ConsultAcceptResponseTest {

    @Test
    void mapsAcceptResultToContractFields() {
        Instant deadline = Instant.parse("2026-07-13T10:00:00Z");
        var result = new ConsultRequestService.AcceptResult(
                "req-abc123", ConsultRequestState.ACCEPTED_AWAIT_PAY, deadline);

        ConsultAcceptResponse dto = ConsultAcceptResponse.of(result);

        assertThat(dto.requestToken()).isEqualTo("req-abc123");
        assertThat(dto.state()).isEqualTo("ACCEPTED_AWAIT_PAY"); // 枚举 name() UPPER_SNAKE
        assertThat(dto.payDeadlineAt()).isEqualTo(deadline);     // 服务端权威 timestamptz 原样透传
    }

    @Test
    void payWindowIsNinetySeconds() {
        // 1.5min 限时支付窗，服务端权威计时（不信客户端）。
        assertThat(ConsultRequestService.PAY_WINDOW_SECONDS).isEqualTo(90L);
    }
}
