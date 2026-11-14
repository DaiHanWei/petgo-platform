package com.tailtopia.starterpack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.pay.ChargeRequest;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shared.pay.PaymentGateway;
import com.tailtopia.starterpack.service.StarterPackFulfillmentRetryJob;
import com.tailtopia.starterpack.service.StarterPackFulfillmentService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * L1（需 postgres + redis，云端不跑）：V1.5.0 gift-pack Story 4-2 · AC5.5 —— stub 网关回调 → PAID → 发放完成；回调重放不重复发放；
 * 发放失败后重试任务补齐；过窗后才到账照样 PAID + 发放。
 */
class StarterPackQrisIntegrationTest extends ApiIntegrationTest {

    @MockitoBean
    private GrowthQueryService growth;
    @MockitoBean
    private PaymentGateway gateway;
    @MockitoSpyBean
    private StarterPackFulfillmentService fulfillment;

    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private StarterPackFulfillmentRetryJob retryJob;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        when(growth.getLevel(anyLong())).thenReturn(GrowthLevel.of(1, GrowthTier.NEWBIE));
        when(gateway.createCharge(any(ChargeRequest.class))).thenAnswer(inv -> {
            ChargeRequest r = inv.getArgument(0);
            return new ChargeResult("gw-" + r.orderId(), "QR-" + r.orderId(), Map.of());
        });
    }

    private JsonNode buyQris(long uid, int tier, String requestId) throws Exception {
        String body = mvc.perform(post("/api/v1/starter-pack/purchases")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(uid)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("tier", tier, "channel", "QRIS", "requestId", requestId))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private void pay(String intentToken) {
        paymentIntents.applyCallback(new PaymentCallback(intentToken, "gw-" + intentToken, GatewayStatus.PAID, Map.of()));
    }

    private String purchaseStatus(String purchaseToken) {
        return jdbc.queryForObject("SELECT status FROM starter_pack_purchases WHERE public_token = ?", String.class,
                purchaseToken);
    }

    private boolean fulfilled(String purchaseToken) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT fulfilled_at IS NOT NULL FROM starter_pack_purchases WHERE public_token = ?", Boolean.class,
                purchaseToken));
    }

    private long credits(long uid) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM entitlement_credits WHERE user_id = ?", Long.class, uid);
        return n == null ? 0 : n;
    }

    @Test
    void callbackPaysAndFulfillsOnceEvenWhenReplayed() throws Exception {
        User u = newUser();
        JsonNode r = buyQris(u.getId(), 3, "q1");
        String intent = r.get("intent").get("token").asString();
        String token = r.get("purchaseToken").asString();
        pay(intent);
        assertThat(purchaseStatus(token)).isEqualTo("PAID");
        assertThat(fulfilled(token)).isTrue();
        assertThat(credits(u.getId())).isEqualTo(10);
        pay(intent);
        assertThat(credits(u.getId())).isEqualTo(10);
    }

    @Test
    void failedFulfillmentIsRetriedByJob() throws Exception {
        User u = newUser();
        JsonNode r = buyQris(u.getId(), 1, "q1");
        String token = r.get("purchaseToken").asString();
        doThrow(new IllegalStateException("boom")).when(fulfillment).fulfill(anyLong());
        pay(r.get("intent").get("token").asString());
        assertThat(purchaseStatus(token)).isEqualTo("PAID");
        assertThat(fulfilled(token)).isFalse();

        doCallRealMethod().when(fulfillment).fulfill(anyLong());
        jdbc.update("UPDATE starter_pack_purchases SET paid_at = now() - interval '5 minutes' WHERE public_token = ?",
                token);
        retryJob.run();
        assertThat(fulfilled(token)).isTrue();
        assertThat(credits(u.getId())).isEqualTo(9);
    }

    @Test
    void paidAfterWindowStillFulfills() throws Exception {
        User u = newUser();
        JsonNode r = buyQris(u.getId(), 1, "q1");
        String token = r.get("purchaseToken").asString();
        jdbc.update("UPDATE starter_pack_purchases SET status = 'EXPIRED' WHERE public_token = ?", token);
        pay(r.get("intent").get("token").asString());
        assertThat(purchaseStatus(token)).isEqualTo("PAID");
        assertThat(fulfilled(token)).isTrue();
    }
}
