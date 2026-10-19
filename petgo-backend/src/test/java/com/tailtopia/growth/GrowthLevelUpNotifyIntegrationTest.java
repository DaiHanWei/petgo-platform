package com.tailtopia.growth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.service.AccrualCommand;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/** V1.4.0 ① Story 2.1 · AC2 – AC4（L1，需 Docker postgres + redis）：升级通知落库 + popups 接口端到端。 */
class GrowthLevelUpNotifyIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private GrowthLedgerService ledger;

    @Autowired
    private JdbcTemplate jdbc;

    private static final Instant AT = Instant.parse("2026-10-18T05:00:00Z");

    private int notifications(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = 'GROWTH_LEVEL_UP'",
                Integer.class, userId);
    }

    private static void await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        Thread.sleep(150);
    }

    private void gift(long u, int points, String key) {
        ledger.accrue(new AccrualCommand(u, RuleCode.GIFT_SENT, points, key, "GIFT_ORDER", key, null, AT));
    }

    @Test
    void levelUpNotifiesAndPopupsFlow() throws Exception {
        long u = newUser().getId();
        gift(u, 495, "lvup-a-" + u);
        await(() -> false);
        int baseline = notifications(u); // 1 → 4 也是升级，算一条
        gift(u, 5, "lvup-b-" + u); // 500 → Lv5
        await(() -> notifications(u) == baseline + 1);

        assertThat(notifications(u)).isEqualTo(baseline + 1);
        assertThat(jdbc.queryForObject("SELECT target_ref FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'GROWTH_LEVEL_UP' ORDER BY id DESC LIMIT 1", String.class, u)).isEqualTo("5:BUDDY");
        assertThat(jdbc.queryForObject("SELECT deep_link_type FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'GROWTH_LEVEL_UP' ORDER BY id DESC LIMIT 1", String.class, u)).isEqualTo("GROWTH_LEVEL_UP");

        mvc.perform(get("/api/v1/me/growth/popups").header(HttpHeaders.AUTHORIZATION, userBearer(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.celebration.fromLevel").value(1))
                .andExpect(jsonPath("$.celebration.toLevel").value(5))
                .andExpect(jsonPath("$.celebration.tierUp").value(true))
                .andExpect(jsonPath("$.backfill").doesNotExist());

        gift(u, 800, "lvup-c-" + u); // 1300 → Lv7
        await(() -> notifications(u) == baseline + 2);
        mvc.perform(post("/api/v1/me/growth/popups/celebration/ack").param("toLevel", "7")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/growth/popups").header(HttpHeaders.AUTHORIZATION, userBearer(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.celebration").doesNotExist());
        mvc.perform(post("/api/v1/me/growth/popups/nope/ack").header(HttpHeaders.AUTHORIZATION, userBearer(u)))
                .andExpect(status().isBadRequest());
    }
}
