package com.tailtopia.gift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.gift.dto.GiftSendRequest;
import com.tailtopia.gift.dto.GiftSendResponse;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.gift.service.GiftSendService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * V1.4.0 gift Story 2.3 · AC1 / AC10b · L1（需 postgres + redis，云端不跑）：{@code GET /api/v1/me/gift-orders}。
 *
 * <p>三笔经 1.1 / 1.2 真实路径造：QRIS 待付、PawCoin 已送达、付款前删宠物 → 自动退回；45 笔分页用直写订单造
 * （只读端点，与状态机无关）。
 */
class MeGiftOrderEndpointTest extends ApiIntegrationTest {

    @Autowired
    private GiftSendService sendService;
    @Autowired
    private GiftCatalogRepository catalog;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private VetTestSupport vets;

    private GiftCatalogItem tenK;

    @BeforeEach
    void seedCatalog() {
        long n = SEQ.incrementAndGet();
        tenK = catalog.save(GiftCatalogItem.create("mo" + n, "Bone" + n, "Tulang" + n, "https://cdn/bone.png", 10_000,
                false, false, false, GiftKind.NORMAL, null, 0));
    }

    private PetProfile newPet(User owner) {
        return petProfiles.save(PetProfile.create(owner.getId(), PetType.CAT, "Mochi", null, null, null, null,
                "MO-" + SEQ.incrementAndGet()));
    }

    private GiftSendResponse send(User sender, PetProfile pet, PayChannel channel, int qty) {
        return sendService.send(sender.getId(), pet.getId(), new GiftSendRequest(tenK.getPublicToken(), qty, channel,
                "mo-" + SEQ.incrementAndGet(), "gift_page", null));
    }

    private void pay(GiftSendResponse r) {
        paymentIntents.applyCallback(new PaymentCallback(r.payment().token(), "stub-" + r.payment().token(),
                GatewayStatus.PAID, Map.of()));
    }

    private JsonNode list(long userId, String cursor) throws Exception {
        var req = get("/api/v1/me/gift-orders").header(HttpHeaders.AUTHORIZATION, userBearer(userId));
        if (cursor != null) {
            req = req.param("cursor", cursor);
        }
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private void insertOrder(long sender, long petId, long owner, String status) {
        jdbc.update("""
                INSERT INTO gift_orders (public_token, sender_id, pet_id, owner_id, pet_name, gift_id, gift_name_en,
                    gift_name_id, gift_icon_url, unit_price, quantity, total, channel, status, source, idem_key,
                    created_at)
                VALUES (?, ?, ?, ?, 'Mochi', ?, 'Bone', 'Tulang', 'https://cdn/bone.png', 10000, 1, 10000, 'QRIS',
                    ?, 'gift_page', ?, now() - (? * interval '1 second'))""",
                "mo" + SEQ.incrementAndGet(), sender, petId, owner, tenK.getId(), status,
                "GIFT:TEST:" + SEQ.incrementAndGet(), SEQ.incrementAndGet() % 1000);
    }

    /** AC1.5 / AC1.6 / AC10b：待付 / 已送达 / 已退回三行倒序；付款后同一行变 DELIVERED；删宠物行 pet = null。 */
    @Test
    void threeStatusesNewestFirstAndQrisBecomesDeliveredAfterPayment() throws Exception {
        User sender = newUser();
        User owner = newUser();
        // 多宠物（gift Story 4.1）：「付款前被删的宠物」与在用的宠物同属一位主人。
        PetProfile goneLater = newPet(owner);
        PetProfile pet = newPet(owner);

        GiftSendResponse refunded = send(sender, goneLater, PayChannel.QRIS, 1);
        jdbc.update("DELETE FROM pet_profiles WHERE id = ?", goneLater.getId());
        pay(refunded);
        wallet.credit(sender.getId(), 20_000, PawCoinTxnType.TOPUP, "TEST", null,
                "mo-topup:" + SEQ.incrementAndGet());
        GiftSendResponse delivered = send(sender, pet, PayChannel.PAWCOIN, 2);
        GiftSendResponse pending = send(sender, pet, PayChannel.QRIS, 3);

        JsonNode r = list(sender.getId(), null);
        JsonNode items = r.get("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("orderToken").asText()).isEqualTo(pending.orderToken());
        assertThat(items.get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(items.get(0).get("channel").asText()).isEqualTo("QRIS");
        assertThat(items.get(0).get("quantity").asInt()).isEqualTo(3);
        assertThat(items.get(0).get("total").asLong()).isEqualTo(30_000L);
        assertThat(items.get(0).get("pet").get("petId").asLong()).isEqualTo(pet.getId());
        assertThat(items.get(0).get("gift").get("unitPrice").asLong()).isEqualTo(10_000L);
        assertThat(items.get(1).get("orderToken").asText()).isEqualTo(delivered.orderToken());
        assertThat(items.get(1).get("status").asText()).isEqualTo("DELIVERED");
        assertThat(items.get(1).get("channel").asText()).isEqualTo("PAWCOIN");
        assertThat(items.get(2).get("orderToken").asText()).isEqualTo(refunded.orderToken());
        assertThat(items.get(2).get("status").asText()).isEqualTo("UNDELIVERED_REFUNDED");
        assertThat(items.get(2).has("pet")).isFalse();
        assertThat(items.get(2).get("petGone").asBoolean()).isTrue();
        assertThat(items.get(2).get("gift").get("nameEn").asText()).isEqualTo(tenK.getNameEn());
        assertThat(r.has("nextCursor")).isFalse();
        assertThat(r.toString()).doesNotContain("senderId").doesNotContain("ownerId")
                .doesNotContain("paymentIntentId").doesNotContain("idemKey").doesNotContain("petName");

        pay(pending);
        assertThat(list(sender.getId(), null).get("items").get(0).get("status").asText()).isEqualTo("DELIVERED");

        // 他人看不到；?userId= 被忽略。
        assertThat(list(owner.getId(), null).get("items")).isEmpty();
        JsonNode viaParam = json.readTree(mvc.perform(get("/api/v1/me/gift-orders")
                        .param("userId", String.valueOf(sender.getId()))
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(viaParam.get("items")).isEmpty();
    }

    /** AC1.7：45 笔含 2 笔 EXPIRED（另 1 笔 FAILED）→ 20 / 20 / 3，无重复无遗漏，末页无 nextCursor。 */
    @Test
    void paginates20_20_3AndSkipsExpiredAndFailed() throws Exception {
        User sender = newUser();
        User owner = newUser();
        PetProfile pet = newPet(owner);
        for (int i = 0; i < 43; i++) {
            insertOrder(sender.getId(), pet.getId(), owner.getId(), i % 2 == 0 ? "DELIVERED" : "PENDING");
        }
        insertOrder(sender.getId(), pet.getId(), owner.getId(), "EXPIRED");
        insertOrder(sender.getId(), pet.getId(), owner.getId(), "EXPIRED");
        insertOrder(sender.getId(), pet.getId(), owner.getId(), "FAILED");

        List<Integer> sizes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = null;
        do {
            JsonNode page = list(sender.getId(), cursor);
            sizes.add(page.get("items").size());
            for (JsonNode row : page.get("items")) {
                assertThat(seen.add(row.get("orderToken").asText())).isTrue();
                assertThat(row.get("status").asText()).isNotIn("EXPIRED", "FAILED");
            }
            cursor = page.has("nextCursor") ? page.get("nextCursor").asText() : null;
        } while (cursor != null);
        assertThat(sizes).containsExactly(20, 20, 3);
        assertThat(seen).hasSize(43);
    }

    @Test
    void invalidCursorVetAndGuest() throws Exception {
        User sender = newUser();
        mvc.perform(get("/api/v1/me/gift-orders").param("cursor", "%%%bad")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(sender.getId())))
                .andExpect(status().isBadRequest());
        // 兽医 token 须对应 active vet 行：否则 BannedVetFilter 先以「账号不存在」回 401，测不到角色拦截（403）。
        long vetId = vets.newActiveVet("gift-orders-it").getId();
        mvc.perform(get("/api/v1/me/gift-orders").header(HttpHeaders.AUTHORIZATION, vetBearer(vetId)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/gift-orders")).andExpect(status().isUnauthorized());
    }
}
