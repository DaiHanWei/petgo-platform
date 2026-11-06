package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1（v1.4.2 shop Story 6.2 AC1 / AC2）：{@code GET /api/v1/me/shop-orders?group=&cursor=} 与 {@code /counts}。
 * 订单直接插库（各状态 + 一单订单窗已过的待支付），不走下单链路。
 */
class ShopOrderListIntegrationTest extends ApiIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private String insertOrder(long uid, String status, String expiresAtSql, int minutesAgo) {
        String token = "sl" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_orders (public_token, display_no, user_id, status, goods_subtotal,
                        shipping_fee, shipping_discount, total_amount, expires_at,
                        ship_receiver_name, ship_receiver_phone, ship_provinsi,
                        ship_kota_kabupaten, ship_kecamatan, ship_address_line, ship_kode_pos,
                        created_at, updated_at)
                VALUES (?, ?, ?, ?, 100000, 10000, 0, 110000, %s,
                        'Budi', '08123', 'DKI', 'Jaksel', 'Kebayoran', 'Jl. 1', '12110',
                        now() - make_interval(mins => ?), now())
                """.formatted(expiresAtSql), token, "TOKO-IT-" + token, uid, status, minutesAgo);
        return token;
    }

    private Set<String> tokensOf(long uid, String group) throws Exception {
        String body = mvc.perform(get("/api/v1/me/shop-orders").param("group", group)
                        .header("Authorization", userBearer(uid)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) json.readValue(body, Map.class).get("items");
        Set<String> out = new HashSet<>();
        items.forEach(i -> out.add(String.valueOf(i.get("orderToken"))));
        return out;
    }

    @Test
    @DisplayName("8 单覆盖各状态：各 group 结果正确；宽限期单在 cancelled 且不在 pending_payment；counts 与之一致；他人订单不出现")
    void groupsAndCounts() throws Exception {
        long uid = newUser().getId();
        long other = newUser().getId();
        String pp = insertOrder(uid, "PENDING_PAYMENT", "now() + interval '30 minutes'", 1);
        String grace = insertOrder(uid, "PENDING_PAYMENT", "now() - interval '2 minutes'", 62);
        String ps = insertOrder(uid, "PENDING_SHIPMENT", "NULL", 3);
        String sh = insertOrder(uid, "SHIPPED", "NULL", 4);
        String dl = insertOrder(uid, "DELIVERED", "NULL", 5);
        String cp = insertOrder(uid, "COMPLETED", "NULL", 6);
        String cc = insertOrder(uid, "CANCELLED", "NULL", 7);
        String rf = insertOrder(uid, "REFUNDED", "NULL", 8);
        insertOrder(other, "SHIPPED", "NULL", 1);

        assertThat(tokensOf(uid, "all")).containsExactlyInAnyOrder(pp, grace, ps, sh, dl, cp, cc, rf);
        assertThat(tokensOf(uid, "pending_payment")).containsExactly(pp);
        assertThat(tokensOf(uid, "pending_shipment")).containsExactly(ps);
        assertThat(tokensOf(uid, "to_receive")).containsExactlyInAnyOrder(sh, dl);
        assertThat(tokensOf(uid, "completed")).containsExactly(cp);
        assertThat(tokensOf(uid, "cancelled")).containsExactlyInAnyOrder(grace, cc, rf);

        mvc.perform(get("/api/v1/me/shop-orders").param("group", "pending_payment")
                        .header("Authorization", userBearer(uid)))
                .andExpect(jsonPath("$.items[0].displayStatus").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.items[0].paymentDeadline").exists())
                .andExpect(jsonPath("$.items[0].totalAmount").value(110000))
                .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/api/v1/me/shop-orders/counts").header("Authorization", userBearer(uid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingPayment").value(1))
                .andExpect(jsonPath("$.toReceive").value(2));
        mvc.perform(get("/api/v1/me/shop-orders").param("group", "unpaid").header("Authorization", userBearer(uid)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("25 单翻页两页（20 + 5）无重无漏；空集 {items: [], hasMore: false}")
    void pagination() throws Exception {
        long uid = newUser().getId();
        Set<String> all = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            all.add(insertOrder(uid, "COMPLETED", "NULL", i));
        }
        String first = mvc.perform(get("/api/v1/me/shop-orders").header("Authorization", userBearer(uid)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> p1 = json.readValue(first, Map.class);
        String cursor = String.valueOf(p1.get("nextCursor"));
        String second = mvc.perform(get("/api/v1/me/shop-orders").param("cursor", cursor)
                        .header("Authorization", userBearer(uid)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> p2 = json.readValue(second, Map.class);
        List<String> seen = new ArrayList<>();
        for (Map<String, Object> p : List.of(p1, p2)) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) p.get("items");
            items.forEach(i -> seen.add(String.valueOf(i.get("orderToken"))));
        }
        assertThat(seen).hasSize(25).doesNotHaveDuplicates();
        assertThat(new HashSet<>(seen)).isEqualTo(all);

        long empty = newUser().getId();
        mvc.perform(get("/api/v1/me/shop-orders").header("Authorization", userBearer(empty)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    @DisplayName("兽医 token 403；无 token 401")
    void security() throws Exception {
        mvc.perform(get("/api/v1/me/shop-orders").header("Authorization", activeVetBearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/shop-orders/counts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/shop-orders")).andExpect(status().isUnauthorized());
    }
}
