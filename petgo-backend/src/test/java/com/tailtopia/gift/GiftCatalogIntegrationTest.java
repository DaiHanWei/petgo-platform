package com.tailtopia.gift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1（需 postgres + redis，云端只写不跑）：V1.5.0 gift-pack Story 1-1 · AC3.6 —— 真库 {@code categories} 分组、
 * {@code receivedFrom} 三人 + {@code more}、注销送礼人 {@code deleted = true}、旧字段名不变。
 */
class GiftCatalogIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private GiftCatalogRepository catalog;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void categoriesAndReceivedFrom() throws Exception {
        long n = SEQ.incrementAndGet();
        GiftCatalogItem g = catalog.save(GiftCatalogItem.create("gc" + n, "Gift" + n, "Hadiah" + n,
                "https://cdn/g.png", 1_000, false, false, false, GiftKind.NORMAL, null, -2_000_000));
        Long snacks = jdbc.queryForObject("SELECT id FROM gift_categories WHERE name_en = 'Snacks'", Long.class);
        jdbc.update("UPDATE gift_catalog SET category_id = ? WHERE id = ?", snacks, g.getId());

        User owner = newUser();
        PetProfile pet = petProfiles.save(PetProfile.create(owner.getId(), PetType.CAT, "Mochi", null, null, null,
                null, "GC-" + SEQ.incrementAndGet()));
        List<User> senders = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            User s = newUser();
            senders.add(s);
            wallet.credit(s.getId(), 5_000, PawCoinTxnType.TOPUP, "TEST", null, "gc-topup:" + s.getId());
            mvc.perform(post("/api/v1/pets/" + pet.getId() + "/gifts")
                            .header(HttpHeaders.AUTHORIZATION, userBearer(s.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("giftToken", g.getPublicToken(), "quantity", 1,
                                    "channel", "PAWCOIN", "requestId", "gc-" + i, "source", "diary_tab"))))
                    .andExpect(status().isOk());
        }
        // 最近送的那位注销
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", senders.get(3).getId());

        User viewer = newUser();
        String body = mvc.perform(get("/api/v1/gifts/catalog").param("petId", String.valueOf(pet.getId()))
                        .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode root = json.readTree(body);
        for (String f : List.of("recipient", "qrisMinAmount", "tiers", "exclusiveGroups", "categories")) {
            assertThat(root.has(f)).as(f).isTrue();
        }
        JsonNode item = null;
        for (JsonNode c : root.get("categories")) {
            for (JsonNode it : c.get("gifts")) {
                if (it.get("giftToken").asText().equals(g.getPublicToken())) {
                    item = it;
                    assertThat(c.get("nameEn").asText()).isEqualTo("Snacks");
                }
            }
        }
        assertThat(item).isNotNull();
        JsonNode rf = item.get("receivedFrom");
        assertThat(rf.get("senders")).hasSize(3);
        assertThat(rf.get("more").asInt()).isEqualTo(1);
        assertThat(rf.get("senders").get(0).get("deleted").asBoolean()).isTrue();
        assertThat(rf.get("senders").get(0).get("nickname").isNull()).isTrue();
        assertThat(item.has("senderExp")).isTrue();
    }
}
