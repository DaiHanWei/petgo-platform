package com.tailtopia.gift;

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
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1 夹具（V1.5.0 gift-pack Story 1-4）：建礼物 / 宠物、用 PawCoin 真送一笔（返回订单 token）。 */
abstract class GiftOrderTestSupport extends ApiIntegrationTest {

    @Autowired
    protected GiftCatalogRepository catalog;
    @Autowired
    protected PetProfileRepository petProfiles;
    @Autowired
    protected PawCoinWalletService wallet;
    @Autowired
    protected JdbcTemplate jdbc;

    protected GiftCatalogItem newGift(long price, String categoryNameEn) {
        long n = SEQ.incrementAndGet();
        GiftCatalogItem g = catalog.save(GiftCatalogItem.create("go" + n, "Gift" + n, "Hadiah" + n,
                "https://cdn/g.png", price, false, false, false, GiftKind.NORMAL, null, -3_000_000));
        if (categoryNameEn != null) {
            jdbc.update("UPDATE gift_catalog SET category_id = (SELECT id FROM gift_categories WHERE name_en = ?) "
                    + "WHERE id = ?", categoryNameEn, g.getId());
        }
        return g;
    }

    protected PetProfile newPet(User owner) {
        return petProfiles.save(PetProfile.create(owner.getId(), PetType.CAT, "Mochi", null, null, null, null,
                "GO-" + SEQ.incrementAndGet()));
    }

    protected String send(User sender, PetProfile pet, GiftCatalogItem gift, String message) throws Exception {
        wallet.credit(sender.getId(), gift.getPrice(), PawCoinTxnType.TOPUP, "TEST", null,
                "go-topup:" + sender.getId() + ":" + SEQ.incrementAndGet());
        Map<String, Object> body = new HashMap<>(Map.of("giftToken", gift.getPublicToken(), "quantity", 1,
                "channel", "PAWCOIN", "requestId", "go-" + SEQ.incrementAndGet(), "source", "diary_tab"));
        if (message != null) {
            body.put("message", message);
        }
        String resp = mvc.perform(post("/api/v1/pets/" + pet.getId() + "/gifts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(sender.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("orderToken").asText();
    }
}
