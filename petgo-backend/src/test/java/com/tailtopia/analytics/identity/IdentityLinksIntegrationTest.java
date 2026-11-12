package com.tailtopia.analytics.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.auth.domain.User;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * L1：身份校验与关联表端到端（analytics-pipeline Story 1.5 AC1 / AC3 / AC5）——真安全链签 USER token、flusher 真写
 * 埋点测试库 {@code petgo_analytics_test} 的 {@code events_raw} 与 {@code identity_links}。
 *
 * <ul>
 *   <li>前置：本地 {@code createdb petgo_analytics_test}（CI 由 backend-ci.yml 建），Redis 可用。</li>
 *   <li>隔离：每个用例用唯一 {@code messageId} 前缀与随机 {@code anonymousId}，结束按前缀 / 匿名 id 删除，不 TRUNCATE。</li>
 * </ul>
 */
class IdentityLinksIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private JdbcTemplate analyticsJdbc;

    private String prefix;
    private String aid;

    @BeforeEach
    void setUp() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        prefix = "it-ident-" + SEQ.incrementAndGet() + "-";
        aid = UUID.randomUUID().toString();
    }

    @AfterEach
    void cleanUp() {
        analyticsJdbc.update("DELETE FROM identity_links WHERE anonymous_id = ?", aid);
        analyticsJdbc.update("DELETE FROM events_raw WHERE message_id LIKE ?", prefix + "%");
    }

    /** 一条事件；timestamp = sentAt = 现在，故 linked_at ≈ received_at（同请求顺序递增）。 */
    private String event(String id, String eventName, String userKey) {
        String now = Instant.now().toString();
        return "{\"messageId\":\"" + prefix + id + "\",\"event\":\"" + eventName + "\","
                + "\"timestamp\":\"" + now + "\",\"sentAt\":\"" + now + "\","
                + "\"anonymousId\":\"" + aid + "\""
                + (userKey == null ? "" : ",\"userId\":\"" + userKey + "\"") + "}";
    }

    private void send(String event, String bearer) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/v1/analytics/batch")
                .contentType(MediaType.APPLICATION_JSON).content("{\"batch\":[" + event + "]}");
        if (bearer != null) {
            req.header("Authorization", bearer);
        }
        mvc.perform(req).andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(1));
    }

    private void identify(String id, User u) throws Exception {
        send(event(id, LinkTableIdentityResolver.USER_IDENTIFIED, AnalyticsDistinctId.of(u.getId())),
                userBearer(u.getId()));
    }

    private List<Map<String, Object>> awaitLinks(int expected) throws InterruptedException {
        return await("SELECT * FROM identity_links WHERE anonymous_id = ? ORDER BY linked_at", aid, expected);
    }

    private Map<String, Object> awaitEvent(String id) throws InterruptedException {
        List<Map<String, Object>> rows = await("SELECT * FROM events_raw WHERE message_id = ?", prefix + id, 1);
        assertThat(rows).as("事件 %s 应已落库", id).isNotEmpty();
        return rows.get(0);
    }

    private List<Map<String, Object>> await(String sql, String arg, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        List<Map<String, Object>> rows;
        do {
            rows = analyticsJdbc.queryForList(sql, arg);
            if (rows.size() >= expected) {
                return rows;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        return rows;
    }

    @Test
    void loginHistoryAndCrossAccountBackfill() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        String keyA = AnalyticsDistinctId.of(a.getId());
        String keyC = AnalyticsDistinctId.of(c.getId());

        // 先登 A、再登 B、再登回 A：关联表三行，顺序 A、B、A（AC1 / AC5）
        identify("id-1", a);
        awaitLinks(1);
        Thread.sleep(5);
        identify("id-2", b);
        awaitLinks(2);
        Thread.sleep(5);
        identify("id-3", a);
        List<Map<String, Object>> links = awaitLinks(3);
        assertThat(links).extracting(r -> ((Number) r.get("user_id")).longValue())
                .containsExactly(a.getId(), b.getId(), a.getId());
        assertThat(links).extracting(r -> r.get("message_id"))
                .containsExactly(prefix + "id-1", prefix + "id-2", prefix + "id-3");
        assertThat(links.get(0).get("user_key")).isEqualTo(keyA);

        // user_identified 本身通过：user_id = token 用户（AC3）
        Map<String, Object> identified = awaitEvent("id-2");
        assertThat(((Number) identified.get("user_id")).longValue()).isEqualTo(b.getId());
        assertThat(identified.get("identity_unverified")).isEqualTo(false);

        // 补发：B 的 token 发一条声明 A 的普通事件 → 归 A（A 之前在这台设备登录过）
        send(event("backfill", "app_open", keyA), userBearer(b.getId()));
        Map<String, Object> backfill = awaitEvent("backfill");
        assertThat(((Number) backfill.get("user_id")).longValue()).isEqualTo(a.getId());
        assertThat(backfill.get("user_key")).isEqualTo(keyA);
        assertThat(backfill.get("identity_unverified")).isEqualTo(false);

        // 冒充：B 的 token 声明 C（C 从未在此设备登录）→ 剥离 + 标记
        send(event("spoof", "app_open", keyC), userBearer(b.getId()));
        Map<String, Object> spoof = awaitEvent("spoof");
        assertThat(spoof.get("user_key")).isNull();
        assertThat(spoof.get("user_id")).isNull();
        assertThat(spoof.get("identity_unverified")).isEqualTo(true);

        // 无 JWT 带 A → 通过（关联存在）
        send(event("anon-a", "app_open", keyA), null);
        Map<String, Object> anonA = awaitEvent("anon-a");
        assertThat(((Number) anonA.get("user_id")).longValue()).isEqualTo(a.getId());
        assertThat(anonA.get("identity_unverified")).isEqualTo(false);
    }

    @Test
    void sameUserIdentifiedResentOnlyOneLink() throws Exception {
        User a = newUser();

        identify("dup", a);
        awaitLinks(1);
        Thread.sleep(5);
        identify("dup", a);

        // 原始层两行属预期（去重在明细层）；关联只一行（message_id 唯一 + ON CONFLICT DO NOTHING）
        List<Map<String, Object>> raw = await("SELECT * FROM events_raw WHERE message_id = ?", prefix + "dup", 2);
        assertThat(raw).hasSize(2);
        assertThat(awaitLinks(1)).hasSize(1);
    }

    @Test
    void vetTokenCreatesNoLink() throws Exception {
        User a = newUser();
        String vet = activeVetBearer();

        send(event("vet", LinkTableIdentityResolver.USER_IDENTIFIED, AnalyticsDistinctId.of(a.getId())), vet);

        Map<String, Object> row = awaitEvent("vet");
        assertThat(row.get("identity_unverified")).isEqualTo(true);
        assertThat(analyticsJdbc.queryForObject("SELECT count(*) FROM identity_links WHERE anonymous_id = ?",
                Long.class, aid)).isZero();
    }
}
