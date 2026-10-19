package com.tailtopia.growth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Connection;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * V1.4.0 ① Story 2.2（L1，需 Docker postgres + redis）：Flyway 启动已执行一次补算；造数后再用 {@link ScriptUtils}
 * 重放脚本两次，断言补算语义 + 幂等 + 不发通知 + popups。照 {@code MilestoneCheckinTailsonalityBackfillIntegrationTest}。
 */
class GrowthLaunchBackfillIntegrationTest extends ApiIntegrationTest {

    private static final String SCRIPT = "db/migration/V20261018_1503__growth_launch_backfill.sql";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private void runScript() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource(SCRIPT));
        }
    }

    private void activeDays(long userId, int n) {
        for (int i = 0; i < n; i++) {
            jdbc.update("INSERT INTO user_active_days (user_id, active_date) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    userId, LocalDate.of(2026, 9, 25).plusDays(i));
        }
    }

    private Long lifetime(long userId) {
        return jdbc.queryForList("SELECT lifetime_total FROM growth_accounts WHERE user_id = ?", Long.class, userId)
                .stream().findFirst().orElse(null);
    }

    private int ledgerRows(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE user_id = ?", Integer.class, userId);
    }

    @Test
    void backfillIsIdempotentAndSilent() throws Exception {
        User a = newUser();
        activeDays(a.getId(), 60);
        User c = newUser();
        User d = newUser();
        activeDays(d.getId(), 10);
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", d.getId());
        User f = newUser();
        activeDays(f.getId(), 5);
        jdbc.update("INSERT INTO growth_accounts (user_id, lifetime_total, level) VALUES (?, 10, 1)", f.getId());
        User v = users.saveAndFlush(User.newVirtual("virtual:it-bf-" + SEQ.incrementAndGet(), "v", null,
                adminActorId()));
        long notificationsBefore = jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class);

        runScript();
        Integer vLevel = jdbc.queryForObject("SELECT display_level FROM growth_accounts WHERE user_id = ?",
                Integer.class, v.getId());
        runScript();

        assertThat(lifetime(a.getId())).isEqualTo(180L);
        assertThat(ledgerRows(a.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT idem_key FROM growth_ledger WHERE user_id = ?", String.class, a.getId()))
                .isEqualTo(com.tailtopia.growth.service.GrowthIdemKeys.backfill(a.getId()));
        assertThat(jdbc.queryForObject("SELECT level FROM growth_accounts WHERE user_id = ?", Integer.class, a.getId()))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT backfill_active_days FROM growth_accounts WHERE user_id = ?",
                Integer.class, a.getId())).isEqualTo(60);
        assertThat(lifetime(c.getId())).isNull();
        assertThat(ledgerRows(d.getId())).isZero();
        assertThat(lifetime(f.getId())).isEqualTo(25L);
        assertThat(vLevel).isBetween(6, 8);
        assertThat(jdbc.queryForObject("SELECT display_level FROM growth_accounts WHERE user_id = ?", Integer.class,
                v.getId())).isEqualTo(vLevel);
        assertThat(ledgerRows(v.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isEqualTo(notificationsBefore);

        mvc.perform(get("/api/v1/me/growth/popups").header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backfill.activeDays").value(60))
                .andExpect(jsonPath("$.backfill.total").value(180))
                .andExpect(jsonPath("$.celebration").doesNotExist());
        mvc.perform(post("/api/v1/me/growth/popups/backfill/ack").header(HttpHeaders.AUTHORIZATION,
                userBearer(a.getId()))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/growth/popups").header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andExpect(jsonPath("$.backfill").doesNotExist());
    }
}
