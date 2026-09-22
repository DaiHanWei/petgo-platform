package com.tailtopia.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.shared.pay.ChargeRequest;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.PayException;
import com.tailtopia.shared.pay.PaymentGateway;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.triage.domain.UnlockMethod;
import com.tailtopia.triage.dto.TriageSubmitRequest;
import com.tailtopia.triage.dto.UnlockRequest;
import com.tailtopia.triage.service.TriageEventListener;
import com.tailtopia.triage.service.TriageProcessor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * L1 集成：网关下单失败收口（2026-09-21 生产 GemPay 超时事故）。
 *
 * <p>网关第一次抛 {@link PayException}、第二次成功。断言三件事：
 * <ol>
 *   <li>失败那次的意图<b>留在库里</b>、状态 FAILED、reason {@code GATEWAY_CHARGE_FAILED}——
 *       带事务的入口（身份证高清 / AI 解锁）以前会整笔回滚，发给网关的 request_id 就此丢失；</li>
 *   <li>重试成功、拿到载荷；</li>
 *   <li>两次发给网关的 request_id <b>不同</b>——拿旧号重下会撞网关的重复单号（GemPay P02）。</li>
 * </ol>
 */
class GatewayChargeFailureIntegrationTest extends ApiIntegrationTest {

    @MockitoBean
    private PaymentGateway gateway;
    @MockitoBean
    private TriageEventListener triageEventListener; // 隔离 @Async，手动同步驱动 process

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TriageProcessor triageProcessor;

    private void gatewayFailsOnceThenSucceeds() {
        when(gateway.createCharge(any()))
                .thenThrow(new PayException("支付网关收款失败"))
                .thenReturn(new ChargeResult("GW-" + SEQ.incrementAndGet(), "qris-payload", Map.of()));
    }

    /** 断言：该用户该用途恰有一条 FAILED(GATEWAY_CHARGE_FAILED) + 一条 PENDING，且两次 request_id 不同。 */
    private void assertFailedKeptAndRetriedWithNewRequestId(long userId, String purpose, String retryToken) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select public_token, status, gateway_ref, gateway_meta->>'reason' as reason
                from payment_intents where user_id = ? and purpose = ? order by id""", userId, purpose);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("status")).isEqualTo("FAILED");
        assertThat(rows.get(0).get("reason")).isEqualTo("GATEWAY_CHARGE_FAILED");
        assertThat(rows.get(0).get("gateway_ref")).isNull();
        assertThat(rows.get(1).get("status")).isEqualTo("PENDING");
        assertThat(rows.get(1).get("public_token")).isEqualTo(retryToken);
        assertThat(rows.get(1).get("gateway_ref")).isNotNull();

        ArgumentCaptor<ChargeRequest> sent = ArgumentCaptor.forClass(ChargeRequest.class);
        verify(gateway, atLeastOnce()).createCharge(sent.capture());
        List<String> requestIds = sent.getAllValues().stream().map(ChargeRequest::orderId).toList();
        assertThat(requestIds).containsExactly((String) rows.get(0).get("public_token"), retryToken);
    }

    private String tokenOf(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString()).path("payment").path("token").asText();
    }

    @Test
    void idHdChargeFailureKeepsFailedIntentAndRetryUsesNewRequestId() throws Exception {
        User u = newUser();
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Mochi","petType":"CAT","breed":"British","birthday":"2022-01-01"}
                                """))
                .andExpect(status().isCreated());
        gatewayFailsOnceThenSucceeds();

        mvc.perform(post("/api/v1/pet-profiles/me/id-card/hd-download")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"QRIS\"}"))
                .andExpect(status().is5xxServerError());

        String retry = tokenOf(mvc.perform(post("/api/v1/pet-profiles/me/id-card/hd-download")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"QRIS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.token").exists()));

        assertFailedKeptAndRetriedWithNewRequestId(u.getId(), "ID_HD", retry);
    }

    @Test
    void topupChargeFailureKeepsFailedIntentAndRetryUsesNewRequestId() throws Exception {
        User u = newUser();
        gatewayFailsOnceThenSucceeds();

        mvc.perform(post("/api/v1/me/pawcoin/topups")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .header("Idempotency-Key", "topup-fail-" + SEQ.incrementAndGet())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tierId\":\"10k\",\"channel\":\"QRIS\"}"))
                .andExpect(status().is5xxServerError());

        // 同档位 60 分钟内重试：以前会复用那张没码的 PENDING 意图、返回空载荷。
        var res = mvc.perform(post("/api/v1/me/pawcoin/topups")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .header("Idempotency-Key", "topup-retry-" + SEQ.incrementAndGet())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tierId\":\"10k\",\"channel\":\"QRIS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("qris-payload"))
                .andReturn();
        String retry = json.readTree(res.getResponse().getContentAsString()).path("intentToken").asText();

        assertFailedKeptAndRetriedWithNewRequestId(u.getId(), "PAWCOIN_TOPUP", retry);
    }

    @Test
    void aiUnlockChargeFailureKeepsFailedIntentAndRetryUsesNewRequestId() throws Exception {
        User u = newUser();
        var submitted = mvc.perform(post("/api/v1/triage")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new TriageSubmitRequest("最近偶尔打喷嚏，精神食欲都正常", null, null))))
                .andReturn();
        long triageId = json.readTree(submitted.getResponse().getContentAsString()).get("triageId").asLong();
        triageProcessor.process(triageId);
        gatewayFailsOnceThenSucceeds();
        String body = json.writeValueAsString(new UnlockRequest(UnlockMethod.QRIS));

        mvc.perform(post("/api/v1/triage/{id}/unlock", triageId)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is5xxServerError());

        String retry = tokenOf(mvc.perform(post("/api/v1/triage/{id}/unlock", triageId)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.token").exists()));

        assertFailedKeptAndRetriedWithNewRequestId(u.getId(), "AI_UNLOCK", retry);
    }
}
