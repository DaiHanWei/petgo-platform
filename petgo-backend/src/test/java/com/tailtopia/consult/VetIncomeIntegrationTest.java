package com.tailtopia.consult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.VetSettlement;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.VetSettlementRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * L1（需 Docker）。Story 3.7 兽医收入端点 {@code GET /api/v1/vet/income}：当月待结算实时聚合 + 历史月结倒序。
 */
class VetIncomeIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConsultOrderRepository orders;
    @Autowired
    private VetSettlementRepository settlements;
    @Autowired
    private VetTestSupport vets;

    private void seedCompleted(long vetId, long payout, Instant endedAt) {
        ConsultOrder o = ConsultOrder.inProgress("ord-" + SEQ.incrementAndGet(), 9500L + SEQ.incrementAndGet(),
                vetId, 1L, 50000L, PayChannel.PAWCOIN, null, payout, 60, 50000L, endedAt.minusSeconds(600));
        o.markCompleted(endedAt);
        orders.save(o);
    }

    @Test
    void incomeReturnsCurrentMonthAggregateAndHistoryDesc() throws Exception {
        long vetId = vets.newActiveVet("收入医生").getId();
        // 当月两单已完成（实时聚合待结算）。
        seedCompleted(vetId, 30000L, Instant.now());
        seedCompleted(vetId, 30000L, Instant.now());
        // 历史月结两月。
        settlements.save(VetSettlement.of(vetId, "2026-04", 2, 100000L, 60000L, Instant.now()));
        settlements.save(VetSettlement.of(vetId, "2026-05", 3, 150000L, 90000L, Instant.now()));

        mvc.perform(get("/api/v1/vet/income")
                        .header(HttpHeaders.AUTHORIZATION, vetBearer(vetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentMonth.orderCount").value(2))
                .andExpect(jsonPath("$.currentMonth.payoutAmount").value(60000))
                .andExpect(jsonPath("$.currentMonth.grossAmount").value(100000))
                .andExpect(jsonPath("$.currentMonth.status").value("PENDING"))
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[0].period").value("2026-05")) // 倒序：近月在前
                .andExpect(jsonPath("$.history[0].payoutAmount").value(90000))
                .andExpect(jsonPath("$.history[1].period").value("2026-04"));
    }

    @Test
    void incomeEmptyWhenNoData() throws Exception {
        long vetId = vets.newActiveVet("新兽医").getId();
        mvc.perform(get("/api/v1/vet/income")
                        .header(HttpHeaders.AUTHORIZATION, vetBearer(vetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentMonth.orderCount").value(0))
                .andExpect(jsonPath("$.currentMonth.payoutAmount").value(0))
                .andExpect(jsonPath("$.history.length()").value(0));
    }

    @Test
    void nonVetForbiddenAndAnonymousUnauthorized() throws Exception {
        long userId = newUser().getId();
        mvc.perform(get("/api/v1/vet/income")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/vet/income"))
                .andExpect(status().isUnauthorized());
    }
}
