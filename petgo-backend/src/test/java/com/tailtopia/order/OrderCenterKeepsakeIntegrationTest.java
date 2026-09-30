package com.tailtopia.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.order.dto.OrderPage;
import com.tailtopia.order.service.OrderCenterService;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.3.2 batch-a Story 3.6 · L1（需 postgres + redis）：闸门（老 App 逐字一致）、同刻混排翻页不漏不重、详情越权 404、
 * 后台异常页权限与数据可见性。
 */
class OrderCenterKeepsakeIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private OrderCenterService orderCenter;
    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private JdbcTemplate jdbc;

    /** 直接落购买行（三类 × 状态），created_at 可控以造同刻。 */
    private void purchase(long userId, String sku, String status, String createdAt) {
        jdbc.update("""
                INSERT INTO keepsake_purchases (public_token, sku, ref_id, user_id, price_idr, pay_channel, status,
                                                created_at, paid_at)
                VALUES (?, ?, ?, ?, 5000, 'PAWCOIN', ?, ?::timestamptz,
                        CASE WHEN ? IN ('PAID','DUPLICATE_PAID','ORPHAN_PAID') THEN ?::timestamptz END)""",
                "kp" + SEQ.incrementAndGet() + "x".repeat(20), sku, 900000L + SEQ.incrementAndGet(), userId, status,
                createdAt, status, createdAt);
    }

    @Test
    void oldClientSeesNoKeepsakeAndNewClientPagesThroughTiesWithoutLossOrDuplicates() throws Exception {
        User u = newUser();
        long uid = u.getId();
        String t = "2026-09-30T08:00:00Z";
        purchase(uid, "TAILSONALITY", "PAID", t);
        purchase(uid, "PASSPORT_SNAP", "DUPLICATE_PAID", t);
        purchase(uid, "BOARDING_PASS", "ORPHAN_PAID", t);
        purchase(uid, "TAILSONALITY", "PENDING", t);  // 不入
        purchase(uid, "BOARDING_PASS", "EXPIRED", t); // 不入
        // 同刻的充值（既有源）一起混排。
        var intent = paymentIntents.createIntent(uid, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS, 10000, "IDR",
                "ock:" + SEQ.incrementAndGet());
        jdbc.update("UPDATE payment_intents SET status = 'PAID', created_at = ?::timestamptz WHERE public_token = ?", t,
                intent.token());

        OrderPage old = orderCenter.listOrders(uid, null, null, 20, true);
        assertThat(old.items()).extracting(i -> i.orderType()).doesNotContain("TAILSONALITY", "PASSPORT_SNAP",
                "BOARDING_PASS");
        mvc.perform(get("/api/v1/orders").header("Authorization", userBearer(uid)).param("includeEcommerce", "true"))
                .andExpect(jsonPath("$.items[?(@.orderType == 'TAILSONALITY')]").isEmpty());

        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 10; guard++) {
            OrderPage p = orderCenter.listOrders(uid, null, cursor, 1, true, true);
            p.items().forEach(i -> seen.add(i.orderToken()));
            if (!p.hasMore()) {
                break;
            }
            cursor = p.nextCursor();
        }
        assertThat(seen).hasSize(4).doesNotHaveDuplicates();
        Set<String> types = new HashSet<>();
        orderCenter.listOrders(uid, null, null, 20, true, true).items().forEach(i -> types.add(i.orderType()));
        assertThat(types).contains("TAILSONALITY", "PASSPORT_SNAP", "BOARDING_PASS", "PAWCOIN_TOPUP");
    }

    @Test
    void detailIsOwnerOnlyAndUnderReviewForExceptions() throws Exception {
        User u = newUser();
        purchase(u.getId(), "PASSPORT_SNAP", "DUPLICATE_PAID", "2026-09-30T08:00:00Z");
        String token = jdbc.queryForObject("SELECT public_token FROM keepsake_purchases WHERE user_id = ?",
                String.class, u.getId());
        mvc.perform(get("/api/v1/orders/" + token).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.petDeleted").value(true))
                .andExpect(jsonPath("$.targetKind").value("PASSPORT_SNAPSHOT"));
        User other = newUser();
        mvc.perform(get("/api/v1/orders/" + token).header("Authorization", userBearer(other.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminExceptionPageNeedsPaymentViewAndListsOnlyExceptions() throws Exception {
        User u = newUser();
        purchase(u.getId(), "BOARDING_PASS", "ORPHAN_PAID", "2026-09-30T08:00:00Z");
        purchase(u.getId(), "TAILSONALITY", "PAID", "2026-09-30T08:00:00Z");
        mvc.perform(get("/admin/payments/keepsake-exceptions").with(user("ops").authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("payment.view"))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BPASS-")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("TSL-"))));
        mvc.perform(get("/admin/payments/keepsake-exceptions").with(user("nobody").authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("content.view"))))
                .andExpect(status().isForbidden());
    }
}
