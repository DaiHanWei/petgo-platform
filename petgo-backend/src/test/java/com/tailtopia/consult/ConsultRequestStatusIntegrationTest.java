package com.tailtopia.consult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.consult.domain.ConsultRequest;
import com.tailtopia.consult.repository.ConsultRequestRepository;
import com.tailtopia.consult.service.ConsultRequestService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * L1（需 Docker）。Story 3.5 请求状态轮询端点 {@code GET /consultations/{token}}。
 * 前端下单三屏据此驱动 待接单→待支付 跃迁 + 服务端权威倒计时。
 */
class ConsultRequestStatusIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConsultRequestRepository requests;
    @Autowired
    private ConsultRequestService requestService;

    private ConsultRequest seedQueueing(long userId) {
        return requests.save(ConsultRequest.queue(userId, 1L,
                "req-" + SEQ.incrementAndGet(), Instant.now().plus(Duration.ofMinutes(1))));
    }

    @Test
    void queueingStatusReturnsStateAndQueueDeadline() throws Exception {
        long userId = newUser().getId();
        ConsultRequest req = seedQueueing(userId);

        mvc.perform(get("/api/v1/consultations/{t}", req.getRequestToken())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("QUEUEING"))
                .andExpect(jsonPath("$.queueDeadlineAt").isNotEmpty())
                .andExpect(jsonPath("$.payDeadlineAt").doesNotExist()); // 未接单无支付窗
    }

    @Test
    void acceptedStatusReturnsPayDeadline() throws Exception {
        long userId = newUser().getId();
        long vetId = 800_000L + SEQ.incrementAndGet();
        ConsultRequest req = seedQueueing(userId);
        requestService.acceptRequest(vetId, req.getRequestToken()); // → ACCEPTED_AWAIT_PAY + payDeadline

        mvc.perform(get("/api/v1/consultations/{t}", req.getRequestToken())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACCEPTED_AWAIT_PAY"))
                .andExpect(jsonPath("$.payDeadlineAt").isNotEmpty());
    }

    @Test
    void unknownOrOtherUserTokenIs404() throws Exception {
        long userId = newUser().getId();
        long other = newUser().getId();
        ConsultRequest req = seedQueueing(userId);
        // 不存在 → 404
        mvc.perform(get("/api/v1/consultations/{t}", "req-nope-xyz")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().isNotFound());
        // 非本人 → 404（防枚举，不泄露存在性）
        mvc.perform(get("/api/v1/consultations/{t}", req.getRequestToken())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(other)))
                .andExpect(status().isNotFound());
    }

    @Test
    void noJwtUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/consultations/{t}", "req-x")).andExpect(status().isUnauthorized());
    }
}
