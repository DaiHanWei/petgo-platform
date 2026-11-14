package com.tailtopia.gift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.profile.domain.PetProfile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/** L1（需 postgres + redis，云端只写不跑）：V1.5.0 gift-pack Story 1-4 · AC4.7。 */
class GiftOpenIntegrationTest extends GiftOrderTestSupport {

    private ResultActions open(long viewer, long petId, Object body) throws Exception {
        return mvc.perform(post("/api/v1/pets/" + petId + "/gift-orders/open")
                .header(HttpHeaders.AUTHORIZATION, userBearer(viewer))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private long ledgerRows(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE user_id = ?", Long.class, userId);
    }

    @Test
    void singleThenAgainIsAlreadyOpenedAndNoLedgerWrite() throws Exception {
        User owner = newUser();
        PetProfile pet = newPet(owner);
        GiftCatalogItem g = newGift(1_000, "Snacks");
        String t = send(newUser(), pet, g, null);
        long before = ledgerRows(owner.getId());
        JsonNode first = json.readTree(open(owner.getId(), pet.getId(), Map.of("orderTokens", List.of(t)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(first.get("items").get(0).get("alreadyOpened").asBoolean()).isFalse();
        JsonNode again = json.readTree(open(owner.getId(), pet.getId(), Map.of("orderTokens", List.of(t)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(again.get("items").get(0).get("alreadyOpened").asBoolean()).isTrue();
        assertThat(ledgerRows(owner.getId())).as("拆礼物不入账").isEqualTo(before);
    }

    @Test
    void openAllAndForeignTokensIgnoredAndNonOwner403() throws Exception {
        User owner = newUser();
        PetProfile pet = newPet(owner);
        PetProfile other = newPet(newUser());
        GiftCatalogItem g = newGift(1_000, "Snacks");
        send(newUser(), pet, g, null);
        send(newUser(), pet, g, null);
        String foreign = send(newUser(), other, g, null);

        JsonNode ignored = json.readTree(open(owner.getId(), pet.getId(), Map.of("orderTokens", List.of(foreign)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(ignored.get("items")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT opened_at IS NULL FROM gift_orders WHERE public_token = ?",
                Boolean.class, foreign)).isTrue();

        JsonNode all = json.readTree(open(owner.getId(), pet.getId(), Map.of("all", true))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(all.get("items")).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gift_orders WHERE pet_id = ? AND opened_at IS NULL",
                Long.class, pet.getId())).isZero();

        open(newUser().getId(), pet.getId(), Map.of("all", true)).andExpect(status().isForbidden());
        open(owner.getId(), pet.getId(), Map.of()).andExpect(status().isUnprocessableEntity());
        open(owner.getId(), pet.getId(), Map.of("all", true, "orderTokens", List.of("x")))
                .andExpect(status().isUnprocessableEntity());
    }
}
