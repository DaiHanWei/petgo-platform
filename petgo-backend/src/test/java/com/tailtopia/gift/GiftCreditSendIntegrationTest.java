package com.tailtopia.gift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.entitlement.PackCreditTestSupport;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.service.EntitlementCreditService;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1（需 postgres + redis，云端不跑）：V1.5.0 gift-pack Story 4-3 · AC5.6 —— 扣 5 次送 5 个；次数 3 送 5 → 409 无订单；
 * 礼物 5000 → 422；主人有 {@code GIFT_RECEIVED}（按 1000 × 数量）、送礼人无 {@code GIFT_SENT}；送礼人 PawCoin 余额不变。
 */
class GiftCreditSendIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private GiftCatalogRepository catalog;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private EntitlementCreditService credits;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JdbcTemplate jdbc;

    private GiftCatalogItem saveGift(long price) {
        long n = SEQ.incrementAndGet();
        return catalog.save(GiftCatalogItem.create("gc" + n, "Gift" + n, "Hadiah" + n, "https://cdn/g.png", price,
                false, false, false, GiftKind.NORMAL, null, -1_000_000));
    }

    private PetProfile newPet(User owner) {
        return petProfiles.save(PetProfile.create(owner.getId(), PetType.CAT, "Mochi", null, null, null, null,
                "GC-" + SEQ.incrementAndGet()));
    }

    private ResultActions send(long sender, long petId, String giftToken, int qty, String requestId) throws Exception {
        return mvc.perform(post("/api/v1/pets/" + petId + "/gifts").header(HttpHeaders.AUTHORIZATION, userBearer(sender))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("giftToken", giftToken, "quantity", qty, "channel",
                        "PACK_CREDIT", "requestId", requestId, "source", "diary_tab"))));
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void sendsFiveWithFiveCreditsReceiverExpOnlyAndBalanceUnchanged() throws Exception {
        User sender = newUser();
        User owner = newUser();
        PetProfile pet = newPet(owner);
        GiftCatalogItem k = saveGift(1_000);
        PackCreditTestSupport.grant(tx, credits, sender.getId(), CreditType.GIFT_1K, 5, "g-" + sender.getId());
        long before = wallet.balanceOf(sender.getId());
        send(sender.getId(), pet.getId(), k.getPublicToken(), 5, "r1").andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(true)).andExpect(jsonPath("$.senderExp").value(0));
        assertThat(wallet.balanceOf(sender.getId())).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM gift_orders WHERE sender_id = ? AND channel = 'PACK_CREDIT' AND total = 0",
                sender.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM growth_ledger WHERE user_id = ? AND rule_code = 'GIFT_SENT'",
                sender.getId())).isZero();
        assertThat(count("SELECT COALESCE(sum(delta), 0) FROM growth_ledger WHERE user_id = ? AND rule_code = 'GIFT_RECEIVED'",
                owner.getId())).isEqualTo(20);
    }

    @Test
    void insufficientCreditsLeaveNoOrderAndWrongPriceIs422() throws Exception {
        User sender = newUser();
        PetProfile pet = newPet(newUser());
        GiftCatalogItem k = saveGift(1_000);
        GiftCatalogItem fiveK = saveGift(5_000);
        PackCreditTestSupport.grant(tx, credits, sender.getId(), CreditType.GIFT_1K, 3, "g3-" + sender.getId());
        send(sender.getId(), pet.getId(), k.getPublicToken(), 5, "r1").andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM gift_orders WHERE sender_id = ?", sender.getId())).isZero();
        send(sender.getId(), pet.getId(), fiveK.getPublicToken(), 1, "r2").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("gift-credit-not-applicable")));
    }
}
