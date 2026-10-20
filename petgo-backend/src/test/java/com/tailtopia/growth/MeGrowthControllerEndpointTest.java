package com.tailtopia.growth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import com.tailtopia.auth.domain.User;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.service.AccrualCommand;
import com.tailtopia.growth.service.GrowthIdemKeys;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/** V1.4.0 ① Story 1.4 · AC1–AC4（L1，需 Docker postgres + redis）：等级页与明细分页的真接口。 */
class MeGrowthControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private GrowthLedgerService ledger;

    @Autowired
    private com.tailtopia.support.VetTestSupport vets;

    private static final Instant NOW = Instant.now();

    private void gift(long userId, int i) {
        ledger.accrue(new AccrualCommand(userId, RuleCode.GIFT_SENT, 1, "it-ledger-" + userId + "-" + i, "GIFT_ORDER",
                String.valueOf(i), null, NOW));
    }

    @Test
    void ledgerPagesTwentyTwentyFiveWithoutGapsOrDuplicates() throws Exception {
        User u = newUser();
        for (int i = 0; i < 45; i++) {
            gift(u.getId(), i);
        }
        Set<String> seen = new HashSet<>();
        String cursor = null;
        int[] sizes = new int[3];
        for (int pageNo = 0; pageNo < 3; pageNo++) {
            var req = get("/api/v1/me/growth/ledger").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()));
            if (cursor != null) {
                req = req.param("cursor", cursor);
            }
            String body = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            JsonNode n = json.readTree(body);
            sizes[pageNo] = n.get("items").size();
            // 每条流水 created_at 各不相同（逐条入账），以它判重。
            n.get("items").forEach(it -> seen.add(it.get("createdAt").asText()));
            cursor = n.hasNonNull("nextCursor") ? n.get("nextCursor").asText() : null;
        }
        assertThat(sizes).containsExactly(20, 20, 5);
        assertThat(cursor).isNull();
        assertThat(seen).hasSize(45);
    }

    @Test
    void likeGivenMaxesAtFive() throws Exception {
        User u = newUser();
        for (int i = 0; i < 5; i++) {
            ledger.accrue(new AccrualCommand(u.getId(), RuleCode.LIKE_GIVEN, null,
                    GrowthIdemKeys.likeGiven(u.getId(), 9_100_000L + i), "CONTENT_POST", "1", null, NOW));
        }
        mvc.perform(get("/api/v1/me/growth").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participating").value(true))
                .andExpect(jsonPath("$.rules[?(@.code == 'LIKE_GIVEN')].todayCount").value(5))
                .andExpect(jsonPath("$.rules[?(@.code == 'LIKE_GIVEN')].maxedToday").value(true));
    }

    @Test
    void guestIs401AndVetIs403() throws Exception {
        mvc.perform(get("/api/v1/me/growth")).andExpect(status().isUnauthorized());
        // 须是真实存在的在职兽医：鉴权链会回查兽医账号，不存在的 vetId 直接 401，测不到 USER 门本身。
        long vetId = vets.newActiveVet("me-growth").getId();
        mvc.perform(get("/api/v1/me/growth").header(HttpHeaders.AUTHORIZATION, vetBearer(vetId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidCursorIs400() throws Exception {
        User u = newUser();
        mvc.perform(get("/api/v1/me/growth/ledger").param("cursor", "%%%")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isBadRequest());
    }
}
