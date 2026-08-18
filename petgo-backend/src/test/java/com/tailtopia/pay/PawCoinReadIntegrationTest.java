package com.tailtopia.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1（需 Docker postgres+redis）。GET /api/v1/me/pawcoin：余额 + 流水游标分页 + 越权隔离（AC1）。
 */
class PawCoinReadIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PawCoinWalletService walletService;

    @Autowired
    private JdbcTemplate jdbc;

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

    /**
     * 🔴🔴 同刻流水不得在翻页时丢失（2026-08-18 修，{@code action_items} 同一族）。
     *
     * <p><b>钱包流水是这一族里最容易撞上的</b>：一次结算就在同一个事务里写多条
     * （抵扣 + 退款分账 + 补偿），而 Postgres 的 {@code now()} 是<b>事务开始时刻</b> ——
     * 这几条的 {@code created_at} 一模一样。原来的「{@code created_at <} 截断到毫秒的游标」
     * 会把整个同刻组一次跳过。🔴 <b>这是钱的账，少一笔就是对不上。</b>
     */
    @Test
    @DisplayName("🔴🔴 5 笔流水时间戳完全相同 → 逐页翻完，一笔不多一笔不少")
    void ledgerDoesNotSkipRowsSharingTheSameInstant() throws Exception {
        long userId = newUser().getId();
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long delta = 1_000L + i;
            expected.add(delta);
            walletService.credit(userId, delta, PawCoinTxnType.TOPUP, "PAYMENT_INTENT", (long) i,
                    "same-instant-" + SEQ.incrementAndGet());
        }
        // 五笔同刻（精确到微秒都一样）—— 真实场景是「一个事务写多条」，这里直接构造出来，
        // 让缺陷从「偶发」变成「必现」。取这批里最早的真实时间戳，避免与首页哨兵打架。
        jdbc.update("UPDATE pawcoin_transactions SET created_at = "
                + "(SELECT MIN(created_at) FROM pawcoin_transactions WHERE user_id = ?) "
                + "WHERE user_id = ?", userId, userId);

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            JsonNode node = getPawcoin(userId,
                    cursor == null ? "?limit=2" : "?limit=2&cursor=" + cursor);
            for (JsonNode item : node.get("items")) {
                seen.add(item.get("delta").asLong());
            }
            if (!node.get("hasMore").asBoolean()) {
                break;
            }
            cursor = node.get("nextCursor").asString();
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(seen).doesNotHaveDuplicates();
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
