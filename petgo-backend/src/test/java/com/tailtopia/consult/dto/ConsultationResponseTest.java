package com.tailtopia.consult.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.consult.domain.ConsultRequest;
import com.tailtopia.consult.service.ConsultRequestService.CreateResult;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** L0 —— Story 3.2 {@link ConsultationResponse} 映射契约（requestToken/state/queueDeadlineAt/alreadyActive）。 */
class ConsultationResponseTest {

    @Test
    void mapsQueueingRequest() {
        Instant deadline = Instant.now();
        ConsultRequest req = ConsultRequest.queue(7L, 3L, "req-tok", deadline);
        ConsultationResponse r = ConsultationResponse.of(new CreateResult(req, false));
        assertThat(r.requestToken()).isEqualTo("req-tok");
        assertThat(r.state()).isEqualTo("QUEUEING");
        assertThat(r.queueDeadlineAt()).isEqualTo(deadline);
        assertThat(r.alreadyActive()).isFalse();
    }

    @Test
    void alreadyActiveFlagPropagates() {
        ConsultRequest req = ConsultRequest.queue(7L, 3L, "req-tok", Instant.now());
        assertThat(ConsultationResponse.of(new CreateResult(req, true)).alreadyActive()).isTrue();
    }
}
