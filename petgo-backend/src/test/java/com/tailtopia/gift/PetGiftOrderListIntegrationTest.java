package com.tailtopia.gift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.profile.domain.PetProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * L1（需 postgres + redis，云端只写不跑）：V1.5.0 gift-pack Story 1-4 · AC1 / AC2 / AC3 ——
 * 主人未拆在前、访客无 opened 字段、分类过滤、翻页无重复无遗漏（同一 delivered_at 多笔）、宠物被删 404、详情 / 礼物行。
 */
class PetGiftOrderListIntegrationTest extends GiftOrderTestSupport {

    private JsonNode list(long viewer, long petId, String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/pets/" + petId + "/gift-orders" + query)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void ownerUnopenedFirstVisitorNoOpenedAndCategoryFilter() throws Exception {
        User owner = newUser();
        PetProfile pet = newPet(owner);
        GiftCatalogItem snack = newGift(1_000, "Snacks");
        GiftCatalogItem toy = newGift(10_000, "Toys");
        String a = send(newUser(), pet, snack, "hai");
        String b = send(newUser(), pet, toy, null);
        // a 先送：拆掉 b（后送的）→ 主人视角 a（未拆）在 b（已拆）前
        mvc.perform(post("/api/v1/pets/" + pet.getId() + "/gift-orders/open")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("orderTokens", List.of(b)))))
                .andExpect(status().isOk());

        JsonNode mine = list(owner.getId(), pet.getId(), "");
        assertThat(mine.get("items").get(0).get("orderToken").asText()).isEqualTo(a);
        assertThat(mine.get("items").get(0).get("opened").asBoolean()).isFalse();
        assertThat(mine.get("unopenedCount").asInt()).isEqualTo(1);
        assertThat(mine.get("viewerIsOwner").asBoolean()).isTrue();
        assertThat(mine.get("pet").get("petId").asLong()).isEqualTo(pet.getId());
        assertThat(mine.get("categories").size()).isGreaterThanOrEqualTo(5);
        assertThat(mine.get("items").get(0).has("senderId")).isFalse();

        JsonNode unopened = list(owner.getId(), pet.getId(), "?filter=UNOPENED");
        assertThat(unopened.get("items")).hasSize(1);

        User visitor = newUser();
        JsonNode theirs = list(visitor.getId(), pet.getId(), "?filter=UNOPENED");
        assertThat(theirs.get("items")).as("访客 UNOPENED 当 ALL").hasSize(2);
        assertThat(theirs.get("items").get(0).get("orderToken").asText()).isEqualTo(b);
        assertThat(theirs.get("items").get(0).has("opened")).isFalse();
        assertThat(theirs.has("unopenedCount")).isFalse();

        Long toys = jdbc.queryForObject("SELECT id FROM gift_categories WHERE name_en = 'Toys'", Long.class);
        JsonNode filtered = list(owner.getId(), pet.getId(), "?categoryId=" + toys);
        assertThat(filtered.get("items")).hasSize(1);
        assertThat(filtered.get("items").get(0).get("orderToken").asText()).isEqualTo(b);

        // 访客看未拆的详情（含祝福语）
        JsonNode detail = json.readTree(mvc.perform(get("/api/v1/gift-orders/" + a)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(visitor.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.get("message").asText()).isEqualTo("hai");
        assertThat(detail.has("ownerExp")).isFalse();
        assertThat(detail.get("canReport").asBoolean()).isTrue();

        JsonNode preview = json.readTree(mvc.perform(get("/api/v1/pets/" + pet.getId() + "/gift-orders/preview")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(preview.get("giftCount").asLong()).isEqualTo(2);
        assertThat(preview.get("unopenedCount").asInt()).isEqualTo(1);
        assertThat(preview.get("recent")).hasSize(2);
    }

    @Test
    void pagingWithSameDeliveredAtHasNoDuplicatesOrGaps() throws Exception {
        User owner = newUser();
        PetProfile pet = newPet(owner);
        GiftCatalogItem snack = newGift(1_000, "Snacks");
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            sent.add(send(newUser(), pet, snack, null));
        }
        // 同一 delivered_at：强制 id tie-breaker 生效
        jdbc.update("UPDATE gift_orders SET delivered_at = now() WHERE pet_id = ?", pet.getId());
        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = list(newUser().getId(), pet.getId(), cursor == null ? "" : "?cursor=" + cursor);
            for (JsonNode it : page.get("items")) {
                assertThat(seen.add(it.get("orderToken").asText())).isTrue();
            }
            cursor = page.hasNonNull("nextCursor") ? page.get("nextCursor").asText() : null;
            pages++;
        } while (cursor != null);
        assertThat(pages).isEqualTo(2);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(sent);
        mvc.perform(get("/api/v1/pets/" + pet.getId() + "/gift-orders?cursor=bad")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletedPetIs404() throws Exception {
        User owner = newUser();
        PetProfile pet = newPet(owner);
        String token = send(newUser(), pet, newGift(1_000, "Snacks"), null);
        petProfiles.delete(pet);
        mvc.perform(get("/api/v1/pets/" + pet.getId() + "/gift-orders")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/gift-orders/" + token)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isNotFound());
    }
}
