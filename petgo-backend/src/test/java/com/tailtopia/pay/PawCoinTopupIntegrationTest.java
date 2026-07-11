package com.tailtopia.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.pay.repository.PawCoinTransactionRepository;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * L1（需 Docker postgres+redis，stub 网关 dev profile）。充值闭环：下单 → 模拟回调原子到账 → 断言
 * 余额+流水+总账平；回调重放断言不双入账；失败回调断言余额不变（AC1/3/4）。
 */
class PawCoinTopupIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PawCoinWalletService walletService;
    @Autowired
    private PawCoinTransactionRepository txns;

    private String placeTopup(long userId, String tierId, String channel, String idemKey) throws Exception {
        String body = "{\"tierId\":\"" + tierId + "\",\"channel\":\"" + channel + "\"}";
        String resp = mvc.perform(post("/api/v1/me/pawcoin/topups")
                        .header("Authorization", userBearer(userId))
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(resp);
        return node.get("intentToken").asString();
    }

    private void fireCallback(String orderToken, String status) throws Exception {
        String body = "{\"order_id\":\"" + orderToken + "\",\"transaction_id\":\"stub-" + orderToken
                + "\",\"transaction_status\":\"" + status + "\",\"status_code\":\"200\"}";
        mvc.perform(post("/pay/callback").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void topupSettlementCreditsAtomicallyAndIsIdempotent() throws Exception {
        long userId = newUser().getId();
        String token = placeTopup(userId, "10k", "QRIS", "topup-" + SEQ.incrementAndGet());
        assertThat(walletService.balanceOf(userId)).isEqualTo(0L); // 未支付不入账

        // 回调 settlement → 同事务原子到账。
        fireCallback(token, "settlement");
        assertThat(walletService.balanceOf(userId)).isEqualTo(10_000L);
        assertThat(txns.findByUserIdOrderByCreatedAtDesc(userId,
                org.springframework.data.domain.PageRequest.of(0, 10))).hasSize(1);
        assertThat(walletService.reconcile(userId).consistent()).isTrue();

        // 回调重放（双通道）→ 幂等，绝不重复入账。
        fireCallback(token, "settlement");
        assertThat(walletService.balanceOf(userId)).isEqualTo(10_000L);
        assertThat(txns.findByUserIdOrderByCreatedAtDesc(userId,
                org.springframework.data.domain.PageRequest.of(0, 10))).hasSize(1);
    }

    @Test
    void failedCallbackDoesNotCredit() throws Exception {
        long userId = newUser().getId();
        String token = placeTopup(userId, "25k", "DANA", "topup-" + SEQ.incrementAndGet());

        fireCallback(token, "deny"); // 失败态
        assertThat(walletService.balanceOf(userId)).isEqualTo(0L);
        assertThat(txns.findByUserIdOrderByCreatedAtDesc(userId,
                org.springframework.data.domain.PageRequest.of(0, 10))).isEmpty();
    }
}
