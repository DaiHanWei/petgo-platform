package com.tailtopia.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * L1（需 Docker postgres+redis，stub 网关）。Story 1.5：充值选项（档位 + 暂停 flag）+ 支付状态轮询
 * （PENDING→回调→PAID）+ 越权（他人 token→404）。
 */
class PawCoinTopupOptionsIntegrationTest extends ApiIntegrationTest {

    private String placeTopup(long userId) throws Exception {
        String body = "{\"tierId\":\"10k\",\"channel\":\"QRIS\"}";
        String resp = mvc.perform(post("/api/v1/me/pawcoin/topups")
                        .header("Authorization", userBearer(userId))
                        .header("Idempotency-Key", "opt-it-" + SEQ.incrementAndGet())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("intentToken").asString();
    }

    private JsonNode getStatus(long userId, String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/me/pawcoin/topups/" + token + "/status")
                        .header("Authorization", userBearer(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp);
    }

    private void fireCallback(String token, String status) throws Exception {
        // GemPay 回调走 form-urlencoded；stub 网关仍读 Midtrans 字段名（Controller 网关无关）。
        mvc.perform(post("/pay/callback")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("order_id", token)
                        .param("transaction_id", "stub-" + token)
                        .param("transaction_status", status)
                        .param("status_code", "200"))
                .andExpect(status().isOk());
    }

    @Test
    void topupOptionsReturnsTiersAndDefaultNotPaused() throws Exception {
        long userId = newUser().getId();
        String resp = mvc.perform(get("/api/v1/me/pawcoin/topup-options")
                        .header("Authorization", userBearer(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(resp);

        assertThat(node.get("paused").asBoolean()).isFalse(); // 默认不暂停
        assertThat(node.get("tiers").size()).isEqualTo(4);
        assertThat(node.get("tiers").get(0).get("id").asString()).isEqualTo("10k");
        assertThat(node.get("tiers").get(0).get("amount").asLong()).isEqualTo(10_000L);
    }

    @Test
    void statusPollTransitionsPendingToPaid() throws Exception {
        long userId = newUser().getId();
        String token = placeTopup(userId);

        assertThat(getStatus(userId, token).get("status").asString()).isEqualTo("PENDING");
        fireCallback(token, "settlement");
        assertThat(getStatus(userId, token).get("status").asString()).isEqualTo("PAID");
    }

    @Test
    void statusPollRejectsOtherUsersTokenWith404() throws Exception {
        long owner = newUser().getId();
        long other = newUser().getId();
        String token = placeTopup(owner);

        mvc.perform(get("/api/v1/me/pawcoin/topups/" + token + "/status")
                        .header("Authorization", userBearer(other)))
                .andExpect(status().isNotFound()); // 越权枚举他人 token → 404
    }
}
