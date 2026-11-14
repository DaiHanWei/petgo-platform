package com.tailtopia.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1（需 postgres + redis，云端不跑）：V1.5.0 gift-pack Story 4-4 · AC1.6 —— 闸门 false 无礼包行；true 时 PAID + 未过期 PENDING 入列、
 * 过期 PENDING / FAILED 不入；同毫秒混排翻页无漏无重；详情字段。
 */
class OrderCenterStarterPackIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String insert(long uid, String status, String payExpires, String createdAt) {
        String token = "sp" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO starter_pack_purchases (public_token, user_id, tier, price, separate_total, pay_channel,
                    status, idem_key, pay_expires_at, created_at, paid_at, purchase_index)
                VALUES (?, ?, 2, 11000, 18000, 'QRIS', ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz),
                    CASE WHEN ? = 'PAID' THEN now() END, CASE WHEN ? = 'PAID' THEN 1 END)
                """, token, uid, status, "STARTER_PACK:" + uid + ":" + token, payExpires, createdAt, status, status);
        return token;
    }

    private JsonNode page(long uid, String query) throws Exception {
        String body = mvc.perform(get("/api/v1/orders" + query).header(HttpHeaders.AUTHORIZATION, userBearer(uid)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @Test
    void gateAndStatusFiltering() throws Exception {
        User u = newUser();
        String paid = insert(u.getId(), "PAID", null, "2026-11-10T00:00:00Z");
        String open = insert(u.getId(), "PENDING", "2099-01-01T00:00:00Z", "2026-11-11T00:00:00Z");
        insert(u.getId(), "EXPIRED", "2026-01-01T00:00:00Z", "2026-11-09T00:00:00Z");
        insert(u.getId(), "FAILED", null, "2026-11-08T00:00:00Z");
        assertThat(page(u.getId(), "").get("items")).isEmpty();
        JsonNode items = page(u.getId(), "?includeStarterPack=true").get("items");
        List<String> tokens = new ArrayList<>();
        items.forEach(i -> tokens.add(i.get("orderToken").asString()));
        assertThat(tokens).containsExactly(open, paid);
        assertThat(items.get(0).get("statusCode").asString()).isEqualTo("PENDING");
        assertThat(items.get(0).get("statusColor").asString()).isEqualTo("WARN");
        assertThat(items.get(1).get("starterPackTier").asInt()).isEqualTo(2);
        JsonNode detail = json.readTree(mvc.perform(get("/api/v1/orders/" + open)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.get("starterPack").get("tier").asInt()).isEqualTo(2);
        assertThat(detail.get("purchaseToken").asString()).isEqualTo(open);
    }

    @Test
    void sameMillisecondMixedPagingHasNoGapsOrDuplicates() throws Exception {
        User u = newUser();
        List<String> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(insert(u.getId(), "PAID", null, "2026-11-10T00:00:00.123456Z"));
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode p = page(u.getId(), "?includeStarterPack=true&limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            p.get("items").forEach(i -> seen.add(i.get("orderToken").asString()));
            cursor = p.hasNonNull("nextCursor") ? p.get("nextCursor").asString() : null;
        } while (cursor != null);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(all).doesNotHaveDuplicates();
    }
}
