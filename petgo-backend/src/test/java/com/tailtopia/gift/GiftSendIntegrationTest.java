package com.tailtopia.gift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.gift.event.GiftSentEvent;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

/**
 * V1.4.0 gift Story 1.1 · AC4 ~ AC7 / AC9 · L1（需 postgres + redis）：真端点走目录与 PawCoin 送礼全链路。
 *
 * <p>礼物由 {@code @BeforeEach} 夹具录入（<b>不写进迁移</b>）：猫 / 狗 / 通用三个 Rp1,000 普通礼物、一个 Rp10,000 通用礼物、
 * Lv5 与 Lv15 两批专属。目录断言只看本测试录入的 token（共享测试库里可能有别的测试的礼物）。
 */
@RecordApplicationEvents
class GiftSendIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private GiftCatalogRepository catalog;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private VetTestSupport vets;
    @Autowired
    private ApplicationEvents events;

    private GiftCatalogItem catGift;
    private GiftCatalogItem dogGift;
    private GiftCatalogItem universal;
    private GiftCatalogItem tenK;
    private GiftCatalogItem exclusive5;

    private GiftCatalogItem saveGift(long price, boolean cat, boolean dog, GiftKind kind, Integer minLevel) {
        long n = SEQ.incrementAndGet();
        return catalog.save(GiftCatalogItem.create("gs" + n, "Gift" + n, "Hadiah" + n, "https://cdn/g.png", price,
                cat, dog, false, kind, minLevel, -1_000_000));
    }

    @BeforeEach
    void seedCatalog() {
        dogGift = saveGift(1_000, false, true, GiftKind.NORMAL, null);
        universal = saveGift(1_000, false, false, GiftKind.NORMAL, null);
        catGift = saveGift(1_000, true, false, GiftKind.NORMAL, null);
        tenK = saveGift(10_000, false, false, GiftKind.NORMAL, null);
        exclusive5 = saveGift(20_000, false, false, GiftKind.EXCLUSIVE, 5);
        saveGift(30_000, false, false, GiftKind.EXCLUSIVE, 15);
    }

    private PetProfile newPet(User owner, PetType type) {
        return petProfiles.save(PetProfile.create(owner.getId(), type, "Mochi", null, null, null, null,
                "GS-" + SEQ.incrementAndGet()));
    }

    private void fund(long uid, long coins) {
        wallet.credit(uid, coins, PawCoinTxnType.TOPUP, "TEST", null, "gift-it-topup:" + uid + ":" + SEQ.incrementAndGet());
    }

    private ResultActions send(long sender, long petId, String giftToken, int qty, String channel, String requestId)
            throws Exception {
        return mvc.perform(post("/api/v1/pets/" + petId + "/gifts")
                .header(HttpHeaders.AUTHORIZATION, userBearer(sender))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("giftToken", giftToken, "quantity", qty, "channel", channel,
                        "requestId", requestId, "source", "diary_tab"))));
    }

    @Test
    void catalogOrdersBySpeciesAndLocksExclusiveForLv1() throws Exception {
        User sender = newUser();
        PetProfile cat = newPet(newUser(), PetType.CAT);
        String body = mvc.perform(get("/api/v1/gifts/catalog").param("petId", String.valueOf(cat.getId()))
                        .header(HttpHeaders.AUTHORIZATION, userBearer(sender.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrisMinAmount").isNumber())
                .andExpect(jsonPath("$.lockBlock.requiredLevel").value(5))
                .andExpect(jsonPath("$.recipient.petId").value(cat.getId()))
                .andReturn().getResponse().getContentAsString();
        int c = body.indexOf(catGift.getPublicToken());
        int u = body.indexOf(universal.getPublicToken());
        int d = body.indexOf(dogGift.getPublicToken());
        assertThat(c).isPositive().isLessThan(u);
        assertThat(u).isLessThan(d);
        assertThat(body).doesNotContain(exclusive5.getPublicToken()).doesNotContain("sortOrder")
                .doesNotContain("forCat");
    }

    @Test
    void pawcoinTenThousandDebitsLedgersDeliversAndPublishesOnce() throws Exception {
        User sender = newUser();
        User owner = newUser();
        PetProfile pet = newPet(owner, PetType.DOG);
        fund(sender.getId(), 50_000);
        long before = wallet.balanceOf(sender.getId());
        long ownerBefore = wallet.balanceOf(owner.getId());

        String token = json.readTree(send(sender.getId(), pet.getId(), tenK.getPublicToken(), 1, "PAWCOIN", "r-1")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.delivered").value(true))
                        .andExpect(jsonPath("$.status").value("DELIVERED"))
                        .andReturn().getResponse().getContentAsString())
                .get("orderToken").asText();

        assertThat(wallet.balanceOf(sender.getId())).isEqualTo(before - 10_000);
        assertThat(wallet.balanceOf(owner.getId())).as("收礼方不入任何币").isEqualTo(ownerBefore);
        Long orderId = jdbc.queryForObject("SELECT id FROM gift_orders WHERE public_token = ?", Long.class, token);
        assertThat(jdbc.queryForObject("SELECT status FROM gift_orders WHERE id = ?", String.class, orderId))
                .isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pawcoin_transactions
                 WHERE user_id = ? AND type = 'SPEND' AND ref_type = 'GIFT' AND ref_id = ?""",
                Long.class, sender.getId(), orderId)).isEqualTo(1L);
        assertThat(jdbc.queryForList("""
                SELECT account || ':' || direction FROM ledger_entries
                 WHERE ref_type = 'GIFT' AND ref_id = ? ORDER BY direction""", String.class, orderId))
                .as("总账 FLOAT_LIABILITY 借 → PLATFORM_REVENUE 贷")
                .containsExactlyInAnyOrder("FLOAT_LIABILITY:DEBIT", "PLATFORM_REVENUE:CREDIT");
        assertThat(jdbc.queryForObject("SELECT count FROM gift_pet_wall WHERE pet_id = ? AND gift_id = ?",
                Long.class, pet.getId(), tenK.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT total_amount FROM gift_pet_supporters WHERE pet_id = ? AND sender_id = ?""",
                Long.class, pet.getId(), sender.getId())).isEqualTo(10_000L);
        assertThat(events.stream(GiftSentEvent.class).filter(e -> e.giftOrderId() == orderId))
                .containsExactly(new GiftSentEvent(orderId, sender.getId(), owner.getId(), pet.getId(), 10_000L));

        mvc.perform(get("/api/v1/me/gift-orders/" + token).header(HttpHeaders.AUTHORIZATION, userBearer(sender.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.petName").value("Mochi"));
        mvc.perform(get("/api/v1/me/gift-orders/" + token).header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isNotFound()); // 仅本人：别人的订单一律 404
    }

    @Test
    void sameRequestIdChargesOnce() throws Exception {
        User sender = newUser();
        PetProfile pet = newPet(newUser(), PetType.CAT);
        fund(sender.getId(), 50_000);
        long before = wallet.balanceOf(sender.getId());
        String a = send(sender.getId(), pet.getId(), catGift.getPublicToken(), 3, "PAWCOIN", "dup-1")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String b = send(sender.getId(), pet.getId(), catGift.getPublicToken(), 3, "PAWCOIN", "dup-1")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(a).get("orderToken").asText()).isEqualTo(json.readTree(b).get("orderToken").asText());
        assertThat(wallet.balanceOf(sender.getId())).isEqualTo(before - 3_000);
    }

    @Test
    void insufficientBalanceLeavesNoOrder() throws Exception {
        User sender = newUser();
        PetProfile pet = newPet(newUser(), PetType.CAT);
        send(sender.getId(), pet.getId(), tenK.getPublicToken(), 1, "PAWCOIN", "poor-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/pawcoin-insufficient"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gift_orders WHERE sender_id = ?", Long.class,
                sender.getId())).isZero();
    }

    @Test
    void validationErrorsMapToProblemTypes() throws Exception {
        User sender = newUser();
        User owner = newUser();
        PetProfile pet = newPet(owner, PetType.CAT);
        send(owner.getId(), pet.getId(), catGift.getPublicToken(), 1, "PAWCOIN", "own")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/gift-own-pet"));
        send(sender.getId(), pet.getId(), exclusive5.getPublicToken(), 1, "PAWCOIN", "lock")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/gift-locked"));
        send(sender.getId(), pet.getId(), catGift.getPublicToken(), 9, "QRIS", "low")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/gift-qris-below-min"));
        send(sender.getId(), pet.getId(), catGift.getPublicToken(), 100, "PAWCOIN", "q100")
                .andExpect(status().isUnprocessableEntity());
        send(sender.getId(), pet.getId(), catGift.getPublicToken(), 1, "MIXED", "mixed")
                .andExpect(status().isUnprocessableEntity());
        send(sender.getId(), Long.MAX_VALUE, catGift.getPublicToken(), 1, "PAWCOIN", "gone")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/gift-pet-gone"));

        jdbc.update("INSERT INTO user_hide_relations (holder_id, target_id, source) VALUES (?, ?, 'BLOCK')",
                sender.getId(), owner.getId());
        send(sender.getId(), pet.getId(), catGift.getPublicToken(), 1, "PAWCOIN", "blocked")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/gift-blocked"));
    }

    @Test
    void ownerBlockingSenderAndBannedOwnerStillReceive() throws Exception {
        User sender = newUser();
        User owner = newUser();
        PetProfile pet = newPet(owner, PetType.CAT);
        fund(sender.getId(), 50_000);
        jdbc.update("INSERT INTO user_hide_relations (holder_id, target_id, source) VALUES (?, ?, 'BLOCK')",
                owner.getId(), sender.getId());
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", owner.getId());
        send(sender.getId(), pet.getId(), catGift.getPublicToken(), 1, "PAWCOIN", "ok-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(true));
        // 只读宠物（② 名额到期）在本分支尚不存在；送礼服务不读任何只读标记，对齐 story 4.1 时在此补断言。
    }

    @Test
    void vetTokenIsForbiddenOnAllThreeEndpoints() throws Exception {
        long vetId = vets.newActiveVet("gift-it").getId();
        PetProfile pet = newPet(newUser(), PetType.CAT);
        mvc.perform(get("/api/v1/gifts/catalog").param("petId", String.valueOf(pet.getId()))
                .header(HttpHeaders.AUTHORIZATION, vetBearer(vetId))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/pets/" + pet.getId() + "/gifts")
                .header(HttpHeaders.AUTHORIZATION, vetBearer(vetId))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/gift-orders/whatever")
                .header(HttpHeaders.AUTHORIZATION, vetBearer(vetId))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/gifts/catalog").param("petId", String.valueOf(pet.getId())))
                .andExpect(status().isUnauthorized());
    }
}
