package com.tailtopia.consult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.consult.domain.ConsultRequest;
import com.tailtopia.consult.domain.ConsultRequestState;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.dto.ConsultPayRequest;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.ConsultRequestRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.consult.service.ConsultRequestService;
import com.tailtopia.admin.failedrequest.repository.FailedConsultRequestRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.im.TencentImClient;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import com.tailtopia.vet.service.VetPresenceService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.ResultActions;

/**
 * L1（需 Docker postgres+redis）。Story 3.4 限时支付、建会话与超时重播。启动即验 V66 契约。
 *
 * <p>核心：PawCoin 同步支付建单+建会话+转单（同事务）；余额不足回滚；现金异步 handler 到账转单；
 * IM 建会话失败——PawCoin 回滚不扣费 / 现金系统故障落地；重播上限封顶；暂停顺延 A-4。
 * IM 客户端 mock（默认返 conv，故障用例注入抛异常）。
 */
class ConsultPayIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConsultRequestRepository requests;
    @Autowired
    private ConsultOrderRepository consultOrders;
    @Autowired
    private ConsultSessionRepository sessions;
    @Autowired
    private ConsultRequestService requestService;
    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private VetPresenceService presence;
    @Autowired
    private FailedConsultRequestRepository failedRequests;
    @Autowired
    private VetTestSupport vets;

    @MockitoBean
    private TencentImClient imClient;

    @BeforeEach
    void stubIm() {
        when(imClient.createConversation(anyString(), anyString())).thenReturn("conv-it-stub");
    }

    private long uniqueVetId() {
        return 900_000L + SEQ.incrementAndGet();
    }

    /** 造一个 ACCEPTED_AWAIT_PAY 请求（seed QUEUEING → 兽医接单 → 支付窗开、兽医 BUSY）。 */
    private ConsultRequest seedAccepted(long userId, long vetId) {
        ConsultRequest r = requests.save(ConsultRequest.queue(userId, 1L,
                "req-" + SEQ.incrementAndGet(), Instant.now().plus(Duration.ofMinutes(1))));
        requestService.acceptRequest(vetId, r.getRequestToken());
        return requests.findByRequestToken(r.getRequestToken()).orElseThrow();
    }

    private ResultActions pay(long userId, String token, PayChannel ch) throws Exception {
        return mvc.perform(post("/api/v1/consultations/{t}/pay", token)
                .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ConsultPayRequest(ch))));
    }

    private void fund(long userId, long coins) {
        wallet.credit(userId, coins, PawCoinTxnType.TOPUP, "SEED", null, "seed-" + SEQ.incrementAndGet());
    }

    // ---- AC3：PawCoin 同步支付建单+建会话+转单 ----

    @Test
    void pawCoinPayBuildsOrderSessionAndConvertsRequest() throws Exception {
        long userId = newUser().getId();
        long vetId = uniqueVetId();
        fund(userId, 50_000L);
        ConsultRequest req = seedAccepted(userId, vetId);
        long ordersBefore = consultOrders.count();

        pay(userId, req.getRequestToken(), PayChannel.PAWCOIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DONE"))
                .andExpect(jsonPath("$.order.orderToken").isNotEmpty())
                .andExpect(jsonPath("$.order.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.order.paidAt").isNotEmpty());

        assertThat(wallet.balanceOf(userId)).isEqualTo(0L);                 // 扣满 50000
        assertThat(consultOrders.count()).isEqualTo(ordersBefore + 1);      // 建单
        assertThat(requests.findByRequestToken(req.getRequestToken())).isEmpty(); // 转单删 request
        // 建 consult_sessions（IN_PROGRESS + IM 会话）——付费问诊复用 Epic 5 会话机器。
        var session = sessions.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(
                userId, List.of(SessionStatus.IN_PROGRESS)).orElseThrow();
        assertThat(session.getImConversationId()).isEqualTo("conv-it-stub");
        assertThat(session.getVetId()).isEqualTo(vetId);
        assertThat(presence.isBusy(vetId)).isTrue();                        // 兽医仍占用（进行中会话）
    }

    @Test
    void pawCoinInsufficientBalanceRollsBackNoOrder() throws Exception {
        long userId = newUser().getId();
        long vetId = uniqueVetId();
        ConsultRequest req = seedAccepted(userId, vetId); // 余额 0
        long ordersBefore = consultOrders.count();

        pay(userId, req.getRequestToken(), PayChannel.PAWCOIN).andExpect(status().isConflict());

        assertThat(consultOrders.count()).isEqualTo(ordersBefore);          // 不建单
        assertThat(requests.findByRequestToken(req.getRequestToken()).orElseThrow().getState())
                .isEqualTo(ConsultRequestState.ACCEPTED_AWAIT_PAY);          // 未转单（回滚）
    }

    @Test
    void secondPayAfterConversionConflicts() throws Exception {
        long userId = newUser().getId();
        long vetId = uniqueVetId();
        fund(userId, 50_000L);
        ConsultRequest req = seedAccepted(userId, vetId);
        pay(userId, req.getRequestToken(), PayChannel.PAWCOIN).andExpect(status().isOk());
        // 已转单删 request → 二次 pay 找不到 → 409（幂等不双扣）。
        pay(userId, req.getRequestToken(), PayChannel.PAWCOIN).andExpect(status().isConflict());
        assertThat(wallet.balanceOf(userId)).isEqualTo(0L); // 只扣一次
    }

    // ---- AC4：现金异步发起 + 到账 handler 转单 ----

    @Test
    void cashPayCreatesIntentThenHandlerConvertsOnCallback() throws Exception {
        long userId = newUser().getId();
        long vetId = uniqueVetId();
        ConsultRequest req = seedAccepted(userId, vetId);
        long ordersBefore = consultOrders.count();

        var res = pay(userId, req.getRequestToken(), PayChannel.QRIS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("PAYMENT_REQUIRED"))
                .andExpect(jsonPath("$.payment.token").isNotEmpty())
                .andReturn();
        String intentToken = json.readTree(res.getResponse().getContentAsString())
                .path("payment").path("token").asText();
        assertThat(requests.findByRequestToken(req.getRequestToken())).isPresent(); // 未删（待到账）
        assertThat(consultOrders.count()).isEqualTo(ordersBefore);                  // 未建单

        // 双通道到账收口 → 发 PaymentIntentPaidEvent → ConsultPaidHandler 同事务转单。
        paymentIntents.applyCallback(new PaymentCallback(
                intentToken, "gw-" + SEQ.incrementAndGet(), GatewayStatus.PAID, Map.of()));

        assertThat(requests.findByRequestToken(req.getRequestToken())).isEmpty();   // 转单删
        assertThat(consultOrders.count()).isEqualTo(ordersBefore + 1);              // 建单
        assertThat(sessions.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(
                userId, List.of(SessionStatus.IN_PROGRESS)).orElseThrow()
                .getImConversationId()).isEqualTo("conv-it-stub");
    }

    // ---- AC5：支付成功但 IM 建会话失败 ----

    @Test
    void pawCoinImFailureRollsBackNoCharge() throws Exception {
        when(imClient.createConversation(anyString(), anyString()))
                .thenThrow(new RuntimeException("IM down"));
        long userId = newUser().getId();
        long vetId = uniqueVetId();
        fund(userId, 50_000L);
        ConsultRequest req = seedAccepted(userId, vetId);
        long ordersBefore = consultOrders.count();

        pay(userId, req.getRequestToken(), PayChannel.PAWCOIN)
                .andExpect(status().isServiceUnavailable()); // 503

        assertThat(wallet.balanceOf(userId)).isEqualTo(50_000L);            // 未扣费（整事务回滚）
        assertThat(consultOrders.count()).isEqualTo(ordersBefore);          // 不建单
        assertThat(requests.findByRequestToken(req.getRequestToken()).orElseThrow().getState())
                .isEqualTo(ConsultRequestState.ACCEPTED_AWAIT_PAY);         // 请求仍待支付（可重试）
    }

    @Test
    void cashImFailureLandsSystemFault() throws Exception {
        when(imClient.createConversation(anyString(), anyString()))
                .thenThrow(new RuntimeException("IM down"));
        long userId = newUser().getId();
        long vetId = uniqueVetId();
        ConsultRequest req = seedAccepted(userId, vetId);
        long ordersBefore = consultOrders.count();
        long failedBefore = failedRequests.count();

        var res = pay(userId, req.getRequestToken(), PayChannel.QRIS).andReturn();
        String intentToken = json.readTree(res.getResponse().getContentAsString())
                .path("payment").path("token").asText();
        // 现金已捕获 → handler IM 失败 → 系统故障落地（不 rethrow，markPaid 提交）。
        paymentIntents.applyCallback(new PaymentCallback(
                intentToken, "gw-" + SEQ.incrementAndGet(), GatewayStatus.PAID, Map.of()));

        assertThat(consultOrders.count()).isEqualTo(ordersBefore);          // 不建单（已扣未交付）
        assertThat(requests.findByRequestToken(req.getRequestToken())).isEmpty(); // 清 request 防再超时
        assertThat(failedRequests.count()).isEqualTo(failedBefore + 1);     // 落 failed(SYSTEM_FAILURE)
    }

    // ---- AC6：重播上限封顶 ----

    @Test
    void rebroadcastCapDeletesAndRecordsTimeout() {
        long userId = newUser().getId();
        long vetId = uniqueVetId();
        ConsultRequest req = seedAccepted(userId, vetId);
        // 造「已重播 5 次 + 支付窗过期」——达上限，应彻底失败不回队。
        ReflectionTestUtils.setField(req, "rebroadcastCount", 5);
        ReflectionTestUtils.setField(req, "payDeadlineAt", Instant.now().minus(Duration.ofSeconds(10)));
        requests.save(req);
        long failedBefore = failedRequests.count();

        requestService.revertExpiredAcceptances();

        assertThat(requests.findById(req.getId())).isEmpty();               // 删（不回队）
        assertThat(failedRequests.count()).isEqualTo(failedBefore + 1);     // failed(TIMEOUT)
        assertThat(presence.isBusy(vetId)).isFalse();                       // 释放兽医
    }

    @Test
    void underCapRequeues(/* 3-3 回归 */) {
        long userId = newUser().getId();
        long vetId = uniqueVetId();
        ConsultRequest req = seedAccepted(userId, vetId); // rebroadcast 0
        ReflectionTestUtils.setField(req, "payDeadlineAt", Instant.now().minus(Duration.ofSeconds(10)));
        requests.save(req);

        requestService.revertExpiredAcceptances();

        ConsultRequest after = requests.findById(req.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(ConsultRequestState.QUEUEING); // 回队
        assertThat(after.getRebroadcastCount()).isEqualTo(1);                 // ++
    }

    // ---- AC7：暂停顺延（A-4）----

    @Test
    void pausedRequestSkippedByScanner() {
        long userId = newUser().getId();
        long vetId = uniqueVetId();
        ConsultRequest req = seedAccepted(userId, vetId);
        requestService.pauseAcceptance(userId, req.getRequestToken());
        // 暂停后即便支付窗过期，扫描器也应跳过（paused_at IS NOT NULL）。
        ConsultRequest paused = requests.findById(req.getId()).orElseThrow();
        ReflectionTestUtils.setField(paused, "payDeadlineAt", Instant.now().minus(Duration.ofSeconds(10)));
        requests.save(paused);

        requestService.revertExpiredAcceptances();

        assertThat(requests.findById(req.getId()).orElseThrow().getState())
                .isEqualTo(ConsultRequestState.ACCEPTED_AWAIT_PAY);          // 未被扫走
    }

    @Test
    void resumeExtendsDeadlineByRemaining() {
        long userId = newUser().getId();
        long vetId = uniqueVetId();
        ConsultRequest req = seedAccepted(userId, vetId);
        requestService.pauseAcceptance(userId, req.getRequestToken());
        assertThat(requests.findById(req.getId()).orElseThrow().getPausedAt()).isNotNull();

        requestService.resumeAcceptance(userId, req.getRequestToken());

        ConsultRequest after = requests.findById(req.getId()).orElseThrow();
        assertThat(after.getPausedAt()).isNull();                            // 清暂停锚
        assertThat(after.getPayDeadlineAt()).isAfter(Instant.now());         // 顺延到未来
    }

    // ---- AC8：契约 / 鉴权 ----

    @Test
    void vetTokenForbiddenOnPay() throws Exception {
        long userId = newUser().getId();
        long vetId = uniqueVetId();
        ConsultRequest req = seedAccepted(userId, vetId);
        // VET 角色（真实 active 账号，过 BannedVetFilter）打用户侧支付端点 → 403（hasRole USER 门控）。
        long realVetId = vets.newActiveVet("支付越权医生").getId();
        mvc.perform(post("/api/v1/consultations/{t}/pay", req.getRequestToken())
                        .header(HttpHeaders.AUTHORIZATION, vetBearer(realVetId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ConsultPayRequest(PayChannel.PAWCOIN))))
                .andExpect(status().isForbidden());
    }

    @Test
    void userCancelDeletesRequestAndRecordsCancel() throws Exception {
        long userId = newUser().getId();
        long vetId = uniqueVetId();
        ConsultRequest req = seedAccepted(userId, vetId);
        long failedBefore = failedRequests.count();

        mvc.perform(post("/api/v1/consultations/{t}/cancel", req.getRequestToken())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().isOk());

        assertThat(requests.findById(req.getId())).isEmpty();               // 物理删
        assertThat(failedRequests.count()).isEqualTo(failedBefore + 1);     // failed(USER_CANCEL)
        assertThat(presence.isBusy(vetId)).isFalse();                       // 释放兽医
    }
}
