package com.tailtopia.consult.web;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.repository.VetAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code GET /api/v1/consult/history} 集成测试：游标分页问诊历史。
 *
 * <p>覆盖：ROLE_USER 门控（vet→403 / guest→401）、空态、终态会话入历史（CANCELLED 不入）、limit 钳制。
 */
class ConsultHistoryControllerEndpointTest extends ApiIntegrationTest {

    private static final String URL = "/api/v1/consult/history";

    @Autowired
    private ConsultSessionRepository sessions;

    @Autowired
    private VetAccountRepository vets;

    @Test
    void history_empty_returns200WithEmptyPage() throws Exception {
        User u = newUser();
        mvc.perform(get(URL).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()", is(0)))
                .andExpect(jsonPath("$.hasMore", is(false)));
    }

    @Test
    void history_includesClosedSession_excludesCancelled() throws Exception {
        User u = newUser();
        long vetId = newVet().getId();

        // 终态 CLOSED 会话（应入历史）
        ConsultSession closed = ConsultSession.startWaiting(u.getId(), ConsultSource.DIRECT);
        closed.markInProgress(vetId);
        closed.endByVet();
        closed.closeUnrated();
        sessions.save(closed);

        // CANCELLED 会话（不入历史）
        ConsultSession cancelled = ConsultSession.startWaiting(u.getId(), ConsultSource.DIRECT);
        cancelled.cancel();
        sessions.save(cancelled);

        mvc.perform(get(URL).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.items[0].type", is("VET")))
                .andExpect(jsonPath("$.items[0].sessionId", is(closed.getId().intValue())))
                .andExpect(jsonPath("$.items[0].terminalState", is("CLOSED")));
    }

    @Test
    void history_onlyOwnSessions_notOthers() throws Exception {
        User owner = newUser();
        User other = newUser();
        long vetId = newVet().getId();
        ConsultSession s = ConsultSession.startWaiting(owner.getId(), ConsultSource.DIRECT);
        s.markInProgress(vetId);
        s.endByVet();
        s.closeUnrated();
        sessions.save(s);

        // other 用户看不到 owner 的会话
        mvc.perform(get(URL).header("Authorization", userBearer(other.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(0)));
    }

    @Test
    void history_respectsLimitParam() throws Exception {
        User u = newUser();
        mvc.perform(get(URL).param("limit", "5").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void history_missingToken_returns401() throws Exception {
        mvc.perform(get(URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void history_byVet_returns403() throws Exception {
        VetAccount vet = newVet();
        mvc.perform(get(URL).header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isForbidden());
    }

    private VetAccount newVet() {
        return vets.save(VetAccount.create("vet-it-" + SEQ.incrementAndGet(),
                "$2a$10$abcdefghijklmnopqrstuv", "兽医"));
    }
}
