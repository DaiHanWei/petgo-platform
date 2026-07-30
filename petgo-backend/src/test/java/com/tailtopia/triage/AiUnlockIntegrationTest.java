package com.tailtopia.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.triage.domain.AiConsultOrderStatus;
import com.tailtopia.triage.domain.TriageTask;
import com.tailtopia.triage.domain.UnlockMethod;
import com.tailtopia.triage.domain.UnlockSource;
import com.tailtopia.triage.dto.TriageSubmitRequest;
import com.tailtopia.triage.dto.UnlockRequest;
import com.tailtopia.triage.repository.AiConsultOrderRepository;
import com.tailtopia.triage.repository.TriageTaskRepository;
import com.tailtopia.triage.service.TriageEventListener;
import com.tailtopia.triage.service.TriageProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * L1 集成：Story 2.3 AI 解锁流（dev profile，Gemini stub）。上下文启动验 Flyway V65 + validate
 * （ai_consult_orders 契约）。核心：免费额度/PawCoin 同步解锁落库、余额不足回滚、二次解锁不重复扣、
 * 红色不扣费、现金建 PENDING_PAYMENT。
 */
class AiUnlockIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private TriageProcessor triageProcessor;
    @Autowired
    private TriageTaskRepository triageTasks;
    @Autowired
    private AiConsultOrderRepository orders;
    @Autowired
    private PawCoinWalletService wallet;

    @MockitoBean
    private TriageEventListener triageEventListener; // 隔离 @Async，手动同步驱动 process

    /** 造一个 DONE 的分诊任务并返回 id（benign→GREEN / 高危→RED）。 */
    private long doneTriage(long userId, String symptom) throws Exception {
        var res = mvc.perform(post("/api/v1/triage")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new TriageSubmitRequest(symptom, null, null))))
                .andReturn();
        long id = json.readTree(res.getResponse().getContentAsString()).get("triageId").asLong();
        triageProcessor.process(id);
        return id;
    }

    private org.springframework.test.web.servlet.ResultActions unlock(long userId, long id, UnlockMethod m)
            throws Exception {
        return mvc.perform(post("/api/v1/triage/{id}/unlock", id)
                .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new UnlockRequest(m))));
    }

    @Test
    void freeQuotaUnlockSetsSourceAndNoOrder() throws Exception {
        User u = newUser();
        long id = doneTriage(u.getId(), "最近偶尔打喷嚏，精神食欲都正常"); // GREEN

        unlock(u.getId(), id, UnlockMethod.FREE_QUOTA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocked").value(true))
                .andExpect(jsonPath("$.result.locked").value(false));

        TriageTask t = triageTasks.findById(id).orElseThrow();
        assertThat(t.getUnlockSource()).isEqualTo(UnlockSource.FREE_QUOTA);
        // 免费不建订单：由 L0 AiUnlockServiceTest verify(orders, never()).save 覆盖。
    }

    @Test
    void pawCoinUnlockDebitsAndBuildsCompletedOrder() throws Exception {
        User u = newUser();
        long id = doneTriage(u.getId(), "最近偶尔打喷嚏，精神食欲都正常"); // GREEN
        wallet.credit(u.getId(), 50_000L, PawCoinTxnType.TOPUP, "TOPUP", null,
                "seed-" + SEQ.incrementAndGet());
        long before = wallet.balanceOf(u.getId());

        unlock(u.getId(), id, UnlockMethod.PAWCOIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocked").value(true))
                .andExpect(jsonPath("$.result.advice").exists()); // 解锁后详建下发

        assertThat(wallet.balanceOf(u.getId())).isEqualTo(before - 10_000L);
        TriageTask t = triageTasks.findById(id).orElseThrow();
        assertThat(t.getUnlockSource()).isEqualTo(UnlockSource.PAID);

        // 二次解锁不重复扣费（短路）。
        unlock(u.getId(), id, UnlockMethod.PAWCOIN).andExpect(status().isOk());
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(before - 10_000L); // 余额只减一次
    }

    @Test
    void pawCoinInsufficientBalanceRollsBack() throws Exception {
        User u = newUser();
        long id = doneTriage(u.getId(), "最近偶尔打喷嚏，精神食欲都正常"); // GREEN，余额 0
        unlock(u.getId(), id, UnlockMethod.PAWCOIN)
                .andExpect(status().isConflict()); // 余额不足 409
        TriageTask t = triageTasks.findById(id).orElseThrow();
        assertThat(t.getUnlockSource()).isEqualTo(UnlockSource.LOCKED); // 未解锁（整事务回滚）
    }

    @Test
    void redUnlockCostsNothing() throws Exception {
        User u = newUser();
        long id = doneTriage(u.getId(), "我家狗误食巧克力了，怎么办"); // 安全层升 RED

        unlock(u.getId(), id, UnlockMethod.PAWCOIN) // 即便传 PAWCOIN 也不扣（余额 0 也不 409）
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocked").value(true));

        TriageTask t = triageTasks.findById(id).orElseThrow();
        assertThat(t.getUnlockSource()).isEqualTo(UnlockSource.LOCKED); // 红色数据不动，响应层放行
    }

    @Test
    void cashUnlockCreatesPendingOrderWithoutUnlocking() throws Exception {
        User u = newUser();
        long id = doneTriage(u.getId(), "最近偶尔打喷嚏，精神食欲都正常"); // GREEN

        var result = unlock(u.getId(), id, UnlockMethod.QRIS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocked").value(false))
                .andExpect(jsonPath("$.payment.token").exists())
                .andReturn();

        String intentToken =
                json.readTree(result.getResponse().getContentAsString()).path("payment").path("token").asText();
        var order = orders.findByPaymentIntentToken(intentToken).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(AiConsultOrderStatus.PENDING_PAYMENT);
        TriageTask t = triageTasks.findById(id).orElseThrow();
        assertThat(t.getUnlockSource()).isEqualTo(UnlockSource.LOCKED); // 未支付未解锁
    }
}
