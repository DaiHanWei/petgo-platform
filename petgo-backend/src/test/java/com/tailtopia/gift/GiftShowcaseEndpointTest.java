package com.tailtopia.gift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import com.tailtopia.auth.domain.User;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.4.0 gift Story 2.1 · AC1 ~ AC4 / AC8 · L1（需 postgres + redis）：礼物页、粉丝榜与三个统计端点。
 *
 * <p>数据一律经 1.1 的真实交付路径造（PawCoin 送礼 → 交付同事务维护汇总表），不直接写汇总表。
 */
class GiftShowcaseEndpointTest extends ApiIntegrationTest {

    @Autowired
    private GiftCatalogRepository catalog;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private UserHideRelationService hideService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private VetTestSupport vets;

    private GiftCatalogItem bone;
    private GiftCatalogItem cake;

    @BeforeEach
    void seedCatalog() {
        long n = SEQ.incrementAndGet();
        bone = catalog.save(GiftCatalogItem.create("sb" + n, "Bone", "Tulang", "https://cdn/bone.png", 1_000,
                false, false, false, GiftKind.NORMAL, null, -1_000_000));
        cake = catalog.save(GiftCatalogItem.create("sc" + n, "Cake", "Kue", "https://cdn/cake.png", 50_000,
                false, false, false, GiftKind.NORMAL, null, -1_000_000));
    }

    private PetProfile newPet(User owner) {
        return petProfiles.save(PetProfile.create(owner.getId(), PetType.DOG, "Mochi", null, null, null, null,
                "SH-" + SEQ.incrementAndGet()));
    }

    private void sendDelivered(long sender, long petId, GiftCatalogItem gift, int qty) throws Exception {
        wallet.credit(sender, gift.getPrice() * qty, PawCoinTxnType.TOPUP, "TEST", null,
                "gift-show-topup:" + sender + ":" + SEQ.incrementAndGet());
        mvc.perform(post("/api/v1/pets/" + petId + "/gifts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(sender))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("giftToken", gift.getPublicToken(), "quantity", qty,
                                "channel", "PAWCOIN", "requestId", "r-" + SEQ.incrementAndGet(),
                                "source", "gift_page"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(true));
    }

    private JsonNode getJson(String url, long viewer) throws Exception {
        return json.readTree(mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, userBearer(viewer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** AC11 / AC17 / AC4：3×🦴 + 1×🎂 → 53000，墙单价降序；主人与访客一致；下线改名改价照旧；退回单不计入；三个统计端点一致。 */
    @Test
    void showcaseTotalsWallAndStatsAgreeForOwnerAndVisitor() throws Exception {
        User owner = newUser();
        PetProfile pet = newPet(owner);
        User a = newUser();
        User c = newUser();
        sendDelivered(a.getId(), pet.getId(), bone, 3);
        sendDelivered(c.getId(), pet.getId(), cake, 1);
        // 一笔已退回的订单（不经交付，不进汇总表）—— 不得计入任何数。
        jdbc.update("""
                INSERT INTO gift_orders (public_token, sender_id, pet_id, owner_id, pet_name, gift_id, gift_name_en,
                    gift_name_id, gift_icon_url, unit_price, quantity, total, channel, status, undelivered_reason,
                    source, idem_key)
                VALUES (?, ?, ?, ?, 'Mochi', ?, 'Cake', 'Kue', 'https://cdn/cake.png', 50000, 1, 50000, 'QRIS',
                    'UNDELIVERED_REFUNDED', 'SENDER_BLOCKED', 'gift_page', ?)""",
                "rf" + SEQ.incrementAndGet(), a.getId(), pet.getId(), owner.getId(), cake.getId(),
                "GIFT:TEST:" + SEQ.incrementAndGet());

        String url = "/api/v1/pets/" + pet.getId() + "/gifts";
        JsonNode asOwner = getJson(url, owner.getId());
        JsonNode asVisitor = getJson(url, newUser().getId());
        assertThat(asOwner.get("viewerIsOwner").asBoolean()).isTrue();
        assertThat(asVisitor.get("viewerIsOwner").asBoolean()).isFalse();
        for (JsonNode r : List.of(asOwner, asVisitor)) {
            assertThat(r.get("totalValue").asLong()).isEqualTo(53_000L);
            assertThat(r.get("giftCount").asLong()).isEqualTo(4L);
            assertThat(r.get("supporterCount").asLong()).isEqualTo(2L);
            assertThat(r.get("wall")).hasSize(2);
            assertThat(r.get("wall").get(0).get("nameEn").asText()).isEqualTo("Cake");
            assertThat(r.get("wall").get(1).get("count").asLong()).isEqualTo(3L);
            assertThat(r.toString()).doesNotContain("senderId").doesNotContain("ownerId")
                    .doesNotContain("publicToken").doesNotContain(cake.getPublicToken());
        }
        assertThat(asOwner.get("wall")).isEqualTo(asVisitor.get("wall"));

        // AC17：下线 + 改名改价，墙照旧按快照。
        jdbc.update("UPDATE gift_catalog SET status = 'OFFLINE', name_en = 'Renamed', price = 99000 WHERE id = ?",
                cake.getId());
        JsonNode after = getJson(url, owner.getId());
        assertThat(after.get("wall").get(0).get("nameEn").asText()).isEqualTo("Cake");
        assertThat(after.get("wall").get(0).get("unitPrice").asLong()).isEqualTo(50_000L);
        assertThat(after.get("totalValue").asLong()).isEqualTo(53_000L);

        // AC4：主人 / 站内访客 / 分享 token 三个统计端点 giftTotalValue 一致。
        assertThat(getJson("/api/v1/pet-profiles/me/archive-stats", owner.getId()).get("giftTotalValue").asLong())
                .isEqualTo(53_000L);
        assertThat(getJson("/api/v1/pets/" + pet.getId() + "/visitor/stats", a.getId()).get("giftTotalValue")
                .asLong()).isEqualTo(53_000L);
        JsonNode shared = json.readTree(mvc.perform(get("/api/v1/public/shared-pets/" + pet.getCardToken() + "/stats"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(shared.get("giftTotalValue").asLong()).isEqualTo(53_000L);
        assertThat(shared.has("consultCount")).as("旧版 App 仍在读").isTrue();
    }

    @Test
    void noGiftsIsEmptyButOk() throws Exception {
        User owner = newUser();
        PetProfile pet = newPet(owner);
        JsonNode r = getJson("/api/v1/pets/" + pet.getId() + "/gifts", newUser().getId());
        assertThat(r.get("totalValue").asLong()).isZero();
        assertThat(r.get("giftCount").asLong()).isZero();
        assertThat(r.get("supporterCount").asLong()).isZero();
        assertThat(r.get("wall")).isEmpty();
        assertThat(getJson("/api/v1/pet-profiles/me/archive-stats", owner.getId()).get("giftTotalValue").asLong())
                .isZero();
    }

    /** AC2：25 人 → 20 / 5 两页、名次连续；同额按最近送礼时间倒序；注销送礼人 deleted。 */
    @Test
    void supportersPaginateWithContinuousRanks() throws Exception {
        User owner = newUser();
        PetProfile pet = newPet(owner);
        List<User> senders = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            User s = newUser();
            senders.add(s);
            sendDelivered(s.getId(), pet.getId(), bone, 1 + (i % 5));
        }
        User gone = senders.get(24);
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", gone.getId());

        String url = "/api/v1/pets/" + pet.getId() + "/gifts/supporters";
        JsonNode p1 = getJson(url, newUser().getId());
        assertThat(p1.get("items")).hasSize(20);
        assertThat(p1.get("items").get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(p1.get("items").get(0).get("totalAmount").asLong()).isEqualTo(5_000L);
        assertThat(p1.get("items").get(0).get("gifts").get(0).get("nameEn").asText()).isEqualTo("Bone");
        assertThat(p1.toString()).doesNotContain("senderId").doesNotContain("publicToken");
        String cursor = p1.get("nextCursor").asText();

        JsonNode p2 = json.readTree(mvc.perform(get(url).param("cursor", cursor)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(p2.get("items")).hasSize(5);
        assertThat(p2.get("items").get(0).get("rank").asInt()).isEqualTo(21);
        assertThat(p2.has("nextCursor")).isFalse();
        // 最低额（1 个 = Rp1.000）的 5 人在第二页，最后送的排前；注销者 deleted、无昵称。
        long deletedRows = 0;
        for (JsonNode row : p1.get("items")) {
            deletedRows += row.get("deleted").asBoolean() ? 1 : 0;
        }
        List<String> secondPage = new ArrayList<>();
        for (JsonNode row : p2.get("items")) {
            assertThat(row.get("totalAmount").asLong()).isEqualTo(1_000L);
            deletedRows += row.get("deleted").asBoolean() ? 1 : 0;
            secondPage.add(row.get("nickname").asText());
        }
        // 同额（各 1 个）的 5 人：第 0 / 5 / 10 / 15 / 20 位，后送的排前。
        assertThat(secondPage).containsExactly(senders.get(20).getNickname(), senders.get(15).getNickname(),
                senders.get(10).getNickname(), senders.get(5).getNickname(), senders.get(0).getNickname());
        assertThat(deletedRows).isEqualTo(1);

        mvc.perform(get(url).param("cursor", "%%%not-a-cursor")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.traceId").exists());
    }

    /** AC12a + AC3：B 拉黑 A 后 A 送礼 —— B 的榜没有 A、C 有；墙两边都含；我拉黑主人 403、主人拉黑我 404；游客 401；兽医 403。 */
    @Test
    void ownerHidesBlockedSupporterAndVisibilityRules() throws Exception {
        User b = newUser();
        PetProfile pet = newPet(b);
        User a = newUser();
        User d = newUser();
        sendDelivered(d.getId(), pet.getId(), bone, 1);
        hideService.block(b.getId(), a.getId());
        sendDelivered(a.getId(), pet.getId(), cake, 1);

        String url = "/api/v1/pets/" + pet.getId() + "/gifts";
        JsonNode asOwner = getJson(url, b.getId());
        JsonNode asC = getJson(url, newUser().getId());
        assertThat(asOwner.get("supporterCount").asLong()).isEqualTo(1L);
        assertThat(asC.get("supporterCount").asLong()).isEqualTo(2L);
        assertThat(asOwner.get("giftCount").asLong()).isEqualTo(2L);
        assertThat(asC.get("giftCount").asLong()).isEqualTo(2L);

        JsonNode ownerBoard = getJson(url + "/supporters", b.getId());
        assertThat(ownerBoard.get("items")).hasSize(1);
        assertThat(ownerBoard.get("items").get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(ownerBoard.get("items").get(0).get("totalAmount").asLong()).isEqualTo(1_000L);
        assertThat(getJson(url + "/supporters", d.getId()).get("items")).hasSize(2);

        // 我（E）拉黑了主人 → 403；主人拉黑了我（A）→ 404 与不存在同一句。
        User e = newUser();
        hideService.block(e.getId(), b.getId());
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, userBearer(e.getId())))
                .andExpect(status().isForbidden());
        String gone = mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(get("/api/v1/pets/" + Long.MAX_VALUE + "/gifts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(d.getId())))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(gone).get("detail")).isEqualTo(json.readTree(missing).get("detail"));

        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        // 兽医 token 须对应 active vet 行：否则 BannedVetFilter 先以「账号不存在」回 401，测不到角色拦截（403）。
        long vetId = vets.newActiveVet("gift-showcase-it").getId();
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, vetBearer(vetId)))
                .andExpect(status().isForbidden());
        mvc.perform(get(url + "/supporters").header(HttpHeaders.AUTHORIZATION, vetBearer(vetId)))
                .andExpect(status().isForbidden());
    }

    /** P-6：封号主人的礼物页放行（访客 Diary Tab 仍 404）。 */
    @Test
    void bannedOwnerPetStaysVisible() throws Exception {
        User owner = newUser();
        PetProfile pet = newPet(owner);
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", owner.getId());
        getJson("/api/v1/pets/" + pet.getId() + "/gifts", newUser().getId());
        mvc.perform(get("/api/v1/pets/" + pet.getId() + "/visitor/stats")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(newUser().getId())))
                .andExpect(status().isNotFound());
    }
}
