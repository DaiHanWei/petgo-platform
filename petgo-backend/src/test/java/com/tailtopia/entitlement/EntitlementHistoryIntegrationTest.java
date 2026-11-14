package com.tailtopia.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.domain.CreditUseKind;
import com.tailtopia.entitlement.service.ConsumeCommand;
import com.tailtopia.entitlement.service.EntitlementCreditService;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1（需 postgres + redis，云端不跑）：V1.5.0 gift-pack Story 4-4 · AC3.4 —— 发放 + 使用的顺序；送礼跨两批使用合并成一行；翻页。
 */
class EntitlementHistoryIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private EntitlementCreditService credits;
    @Autowired
    private TransactionTemplate tx;

    private JsonNode history(long uid, String cursor) throws Exception {
        String q = cursor == null ? "" : "?cursor=" + cursor;
        return json.readTree(mvc.perform(get("/api/v1/me/entitlements/history" + q)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(uid)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void crossBatchUseIsMergedAndNewestFirst() throws Exception {
        User u = newUser();
        PackCreditTestSupport.grant(tx, credits, u.getId(), CreditType.GIFT_1K, 2, "a-" + u.getId());
        PackCreditTestSupport.grant(tx, credits, u.getId(), CreditType.GIFT_1K, 3, "b-" + u.getId());
        tx.executeWithoutResult(s -> credits.consume(new ConsumeCommand(u.getId(), CreditUseKind.GIFT,
                CreditType.GIFT_1K, 4, 991L, "Mochi")));
        JsonNode items = history(u.getId(), null).get("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("kind").asString()).isEqualTo("USE");
        assertThat(items.get(0).get("quantity").asInt()).isEqualTo(4);
        assertThat(items.get(0).get("petName").asString()).isEqualTo("Mochi");
        assertThat(items.get(1).get("kind").asString()).isEqualTo("GRANT");
        assertThat(items.get(1).get("source").asString()).isEqualTo("PACK");
    }

    @Test
    void pagesOfTwenty() throws Exception {
        User u = newUser();
        for (int i = 0; i < 25; i++) {
            PackCreditTestSupport.grant(tx, credits, u.getId(), CreditType.TAILSONALITY, 1, "p" + i + "-" + u.getId());
        }
        JsonNode first = history(u.getId(), null);
        assertThat(first.get("items")).hasSize(20);
        JsonNode second = history(u.getId(), first.get("nextCursor").asString());
        assertThat(second.get("items")).hasSize(5);
        assertThat(second.hasNonNull("nextCursor")).isFalse();
    }
}
