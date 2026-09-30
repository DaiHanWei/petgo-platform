package com.tailtopia.tailsonality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.repository.PricingConfigRepository;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.service.KeepsakeGranterRegistry;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.tailsonality.domain.TailsonalityCatalog;
import com.tailtopia.tailsonality.service.TailsonalityKeepsakeGranter;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * V1.3.2 batch-a Story 3.2 · L1（需 postgres + redis）：解锁接口两渠道、真实发放口、重测后新旧结果互不影响。
 *
 * <p>注册表用 {@link MockitoBean} 顶掉（3.4 / 3.5 的发放口未交付前上下文起不来），TAILSONALITY 指向<b>真实</b>发放口。
 */
class TailsonalityUnlockIntegrationTest extends ApiIntegrationTest {

    private static final String BASE = "/api/v1/pet-profiles/me/tailsonality/results";

    @MockitoBean
    private KeepsakeGranterRegistry granters;

    @Autowired
    private TailsonalityKeepsakeGranter granter;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private PricingConfigRepository pricingRepo;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        when(granters.forSku(KeepsakeSku.TAILSONALITY)).thenReturn(granter);
    }

    private User userWithPet() {
        User u = newUser();
        petProfiles.save(PetProfile.create(u.getId(), PetType.CAT, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
        return u;
    }

    private String submit(User u) throws Exception {
        String body = TailsonalityCatalog.QUESTION_IDS.stream().map(q -> "\"" + q + "\":1")
                .collect(Collectors.joining(",", "{", "}"));
        String res = mvc.perform(post(BASE).header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("token").asText();
    }

    private org.springframework.test.web.servlet.ResultActions unlock(User u, String token, String channel)
            throws Exception {
        return mvc.perform(post(BASE + "/" + token + "/unlock").header("Authorization", userBearer(u.getId()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"" + channel + "\"}"));
    }

    private boolean unlockedInDb(String token) {
        return jdbc.queryForObject("SELECT unlocked_at IS NOT NULL FROM tailsonality_results WHERE public_token = ?",
                Boolean.class, token);
    }

    @Test
    void pawcoinUnlocksAndSecondAttemptIs409() throws Exception {
        User u = userWithPet();
        wallet.credit(u.getId(), 50_000L, PawCoinTxnType.TOPUP, "TEST", null, "ts-topup:" + SEQ.incrementAndGet());
        long before = wallet.balanceOf(u.getId());
        long price = pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow().getTailsonalityUnlockPrice();
        String token = submit(u);

        unlock(u, token, "PAWCOIN").andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocked").value(true))
                .andExpect(jsonPath("$.purchaseToken").exists());
        assertThat(unlockedInDb(token)).isTrue();
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(before - price);

        unlock(u, token, "PAWCOIN").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/keepsake-already-unlocked"));
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(before - price);
        mvc.perform(get(BASE + "/" + token).header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.unlocked").value(true));
    }

    @Test
    void qrisPaidUnlocksAndRetakeStaysLocked() throws Exception {
        User u = userWithPet();
        String a = submit(u);
        String res = unlock(u, a, "QRIS").andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocked").value(false))
                .andExpect(jsonPath("$.payment.token").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForObject("SELECT status FROM keepsake_purchases WHERE sku = 'TAILSONALITY' AND user_id = ?",
                String.class, u.getId())).isEqualTo("PENDING");
        assertThat(unlockedInDb(a)).isFalse();

        String intent = json.readTree(res).path("payment").path("token").asText();
        paymentIntents.applyCallback(new PaymentCallback(intent, "gw-" + SEQ.incrementAndGet(), GatewayStatus.PAID,
                Map.of()));
        assertThat(unlockedInDb(a)).isTrue();

        String b = submit(u);
        assertThat(unlockedInDb(b)).as("重测的新结果恒为锁态").isFalse();
        assertThat(unlockedInDb(a)).as("旧结果不受影响").isTrue();
    }

    @Test
    void strangerIs404AndMixedIs422() throws Exception {
        User owner = userWithPet();
        String token = submit(owner);
        User stranger = userWithPet();
        unlock(stranger, token, "PAWCOIN").andExpect(status().isNotFound());
        unlock(owner, token, "MIXED").andExpect(status().isUnprocessableEntity());
        unlock(owner, "nope", "PAWCOIN").andExpect(status().isNotFound());
    }
}
