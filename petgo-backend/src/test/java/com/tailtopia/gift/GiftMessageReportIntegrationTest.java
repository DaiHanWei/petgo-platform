package com.tailtopia.gift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.profile.domain.PetProfile;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** L1（需 postgres + redis，云端只写不跑）：V1.5.0 gift-pack Story 1-5 · AC2.5。 */
class GiftMessageReportIntegrationTest extends GiftOrderTestSupport {

    private ResultActions report(long userId, String token) throws Exception {
        return mvc.perform(post("/api/v1/gift-orders/" + token + "/message-reports")
                .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reasonType", "HARASSMENT"))));
    }

    private long reports(String token) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM gift_message_reports r JOIN gift_orders o ON o.id = r.gift_order_id
                 WHERE o.public_token = ?""", Long.class, token);
    }

    @Test
    void reportIdempotentSelfAndNoMessageRejected() throws Exception {
        User owner = newUser();
        PetProfile pet = newPet(owner);
        GiftCatalogItem g = newGift(1_000, "Snacks");
        User sender = newUser();
        String withMsg = send(sender, pet, g, "pesan jelek");
        String noMsg = send(newUser(), pet, g, null);

        User reporter = newUser();
        report(reporter.getId(), withMsg).andExpect(status().isNoContent());
        report(reporter.getId(), withMsg).andExpect(status().isNoContent());
        assertThat(reports(withMsg)).isEqualTo(1);

        report(sender.getId(), withMsg).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("gift-message-not-reportable")));
        report(reporter.getId(), noMsg).andExpect(status().isUnprocessableEntity());
        report(reporter.getId(), "nope" + SEQ.incrementAndGet()).andExpect(status().isNotFound());
    }

    @Test
    void rateLimitedAfterTen() throws Exception {
        User owner = newUser();
        PetProfile pet = newPet(owner);
        String t = send(newUser(), pet, newGift(1_000, "Snacks"), "hai");
        User reporter = newUser();
        for (int i = 0; i < 10; i++) {
            report(reporter.getId(), t).andExpect(status().isNoContent());
        }
        report(reporter.getId(), t).andExpect(status().isTooManyRequests());
    }
}
