package com.tailtopia.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * L1（需 Docker postgres+redis）。GET /api/v1/me/pawcoin：余额 + 流水游标分页 + 越权隔离（AC1）。
 */
class PawCoinReadIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PawCoinWalletService walletService;

    private JsonNode getPawcoin(long userId, String query) throws Exception {
        String resp = mvc.perform(get("/api/v1/me/pawcoin" + query)
                        .header("Authorization", userBearer(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp);
    }

    @Test
    void returnsBalanceAndLedger() throws Exception {
        long userId = newUser().getId();
        walletService.credit(userId, 50_000L, PawCoinTxnType.TOPUP, "PAYMENT_INTENT", 1L,
                "read-it-" + SEQ.incrementAndGet());
        walletService.debit(userId, 5_000L, PawCoinTxnType.SPEND, "AI_UNLOCK", 2L,
                "read-it-" + SEQ.incrementAndGet());

        JsonNode body = getPawcoin(userId, "");

        assertThat(body.get("balance").asLong()).isEqualTo(45_000L);
        JsonNode items = body.get("items");
        assertThat(items.size()).isEqualTo(2);
        // 倒序：最新(SPEND -5000)在前
        assertThat(items.get(0).get("delta").asLong()).isEqualTo(-5_000L);
        assertThat(items.get(0).get("type").asString()).isEqualTo("SPEND");
        // 护栏：不外泄 id/refId/entryGroup
        assertThat(items.get(0).has("id")).isFalse();
        assertThat(items.get(0).has("refId")).isFalse();
        assertThat(items.get(0).has("entryGroup")).isFalse();
    }

    @Test
    void cursorPaginationLimitsPage() throws Exception {
        long userId = newUser().getId();
        for (int i = 0; i < 3; i++) {
            walletService.credit(userId, 1_000L, PawCoinTxnType.TOPUP, "PAYMENT_INTENT", (long) i,
                    "read-pg-" + SEQ.incrementAndGet());
        }
        JsonNode page1 = getPawcoin(userId, "?limit=2");
        assertThat(page1.get("items").size()).isEqualTo(2);
        assertThat(page1.get("hasMore").asBoolean()).isTrue();
        String cursor = page1.get("nextCursor").asString();

        JsonNode page2 = getPawcoin(userId, "?limit=2&cursor=" + cursor);
        assertThat(page2.get("items").size()).isEqualTo(1);
        assertThat(page2.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    void doesNotLeakOtherUsersLedger() throws Exception {
        long a = newUser().getId();
        long b = newUser().getId();
        walletService.credit(a, 30_000L, PawCoinTxnType.TOPUP, "PAYMENT_INTENT", 1L,
                "read-iso-" + SEQ.incrementAndGet());

        JsonNode bodyB = getPawcoin(b, "");
        assertThat(bodyB.get("balance").asLong()).isZero();
        assertThat(bodyB.get("items").size()).isZero();
    }
}
