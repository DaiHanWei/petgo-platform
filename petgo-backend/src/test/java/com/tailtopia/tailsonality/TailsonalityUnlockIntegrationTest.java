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
import com.tailtopia.support.VetTestSupport;
import com.tailtopia.tailsonality.domain.TailsonalityCatalog;
import com.tailtopia.tailsonality.service.TailsonalityKeepsakeGranter;
import com.tailtopia.tailsonality.service.TailsonalityMatchKeepsakeGranter;
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

    @Autowired
    private VetTestSupport vets;

    private static final String BASE = "/api/v1/pet-profiles/me/tailsonality/results";

    @MockitoBean
    private KeepsakeGranterRegistry granters;

    @Autowired
    private TailsonalityKeepsakeGranter granter;
    @Autowired
    private TailsonalityMatchKeepsakeGranter matchGranter;
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
        when(granters.forSku(KeepsakeSku.TS_MATCH)).thenReturn(matchGranter);
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

    /** AC1.5（L1 本地验收 2026-10-05 补）：真实 ACTIVE 兽医 token 打解锁端点 → 403（精确 matcher，不落 anyRequest().authenticated()）。 */
    @Test
    void vetTokenIsForbidden() throws Exception {
        long vetId = vets.newActiveVet("ts-unlock-it").getId();
        mvc.perform(post(BASE + "/someToken/unlock").header("Authorization", vetBearer(vetId))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"channel\":\"PAWCOIN\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * 在真 PostgreSQL 上让「解锁这条结果」的 UPDATE 必定报错（临时触发器，只针对该行），模拟发放时的库级失败。
     * 返回清理动作（finally 里调用）。
     */
    private Runnable failGrantFor(String token) {
        long id = jdbc.queryForObject("SELECT id FROM tailsonality_results WHERE public_token = ?", Long.class, token);
        String fn = "it_fail_grant_" + id;
        jdbc.execute("CREATE FUNCTION " + fn + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.id = " + id + " THEN RAISE EXCEPTION 'it: simulated grant failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + fn + " BEFORE UPDATE ON tailsonality_results FOR EACH ROW EXECUTE FUNCTION " + fn + "()");
        return () -> {
            jdbc.execute("DROP TRIGGER IF EXISTS " + fn + " ON tailsonality_results");
            jdbc.execute("DROP FUNCTION IF EXISTS " + fn + "()");
        };
    }

    /**
     * 本地 L1 验收 2026-10-05：QRIS 到账后发放口在真 PG 上抛库级异常 —— 保存点回滚后外层事务必须还能继续：
     * 意图保持 PAID（到账不丢）、购买行落 ORPHAN_PAID（交人工）、结果仍锁、未自动佩戴。
     */
    @Test
    void qrisPaidButGrantFailsOnRealPostgresIsOrphanPaidAndPaymentKept() throws Exception {
        User u = userWithPet();
        String token = submit(u);
        String res = unlock(u, token, "QRIS").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String intent = json.readTree(res).path("payment").path("token").asText();

        Runnable cleanup = failGrantFor(token);
        try {
            paymentIntents.applyCallback(new PaymentCallback(intent, "gw-" + SEQ.incrementAndGet(), GatewayStatus.PAID,
                    Map.of()));
        } finally {
            cleanup.run();
        }

        assertThat(jdbc.queryForObject("SELECT status FROM payment_intents WHERE public_token = ?", String.class, intent))
                .as("到账不能被发放失败连带回滚").isEqualTo("PAID");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, paid_at, price_idr FROM keepsake_purchases WHERE sku = 'TAILSONALITY' AND user_id = ?",
                u.getId());
        assertThat(row.get("status")).isEqualTo("ORPHAN_PAID");
        assertThat(row.get("paid_at")).isNotNull();
        assertThat(((Number) row.get("price_idr")).longValue()).isPositive();
        assertThat(unlockedInDb(token)).as("发放失败 → 结果仍锁").isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tailsonality_badges b JOIN tailsonality_results r "
                + "ON r.id = b.result_id WHERE r.public_token = ?", Integer.class, token)).isZero();
    }

    /**
     * 本地 L1 验收 2026-10-05：PawCoin 同步发放在真 PG 上失败 —— 按 3-1 记录的保守取舍整笔回滚：
     * 不扣币、不留购买行、结果仍锁、回 409（App 不会显示「已解锁」）。
     */
    @Test
    void pawcoinGrantFailsOnRealPostgresRollsBackWithoutCharging() throws Exception {
        User u = userWithPet();
        wallet.credit(u.getId(), 50_000L, PawCoinTxnType.TOPUP, "TEST", null, "ts-topup:" + SEQ.incrementAndGet());
        long before = wallet.balanceOf(u.getId());
        String token = submit(u);

        Runnable cleanup = failGrantFor(token);
        try {
            unlock(u, token, "PAWCOIN").andExpect(status().isConflict());
        } finally {
            cleanup.run();
        }

        assertThat(wallet.balanceOf(u.getId())).as("发放失败不得扣币").isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM keepsake_purchases WHERE user_id = ?", Integer.class,
                u.getId())).isZero();
        assertThat(unlockedInDb(token)).isFalse();

        // 触发器撤掉后同一结果可正常再买（幂等键没有被失败的那次占住）
        unlock(u, token, "PAWCOIN").andExpect(status().isOk()).andExpect(jsonPath("$.unlocked").value(true));
        assertThat(unlockedInDb(token)).isTrue();
    }

    // ── 2026-10-09 配型改回付费 ─────────────────────────────────────────────────

    /** 单买配型（PawCoin）→ 只开配型；再买完整解读只扣「结果价 − 实付配型价」；之后再买配型 409。 */
    @Test
    void matchThenFullUnlockOnlyChargesTheDifference() throws Exception {
        User u = userWithPet();
        wallet.credit(u.getId(), 50_000L, PawCoinTxnType.TOPUP, "TEST", null, "ts-topup:" + SEQ.incrementAndGet());
        PricingConfig p = pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow();
        long full = p.getTailsonalityUnlockPrice();
        long match = p.getTailsonalityMatchUnlockPrice();
        long before = wallet.balanceOf(u.getId());
        String token = submit(u);
        mvc.perform(get(BASE + "/" + token).header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.matchUnlocked").value(false))
                .andExpect(jsonPath("$.upgradePrice").doesNotExist());

        mvc.perform(post(BASE + "/" + token + "/match-unlock").header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"PAWCOIN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unlocked").value(true));
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(before - match);
        assertThat(unlockedInDb(token)).as("配型不开完整解读").isFalse();
        mvc.perform(get(BASE + "/" + token).header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.unlocked").value(false))
                .andExpect(jsonPath("$.matchUnlocked").value(true))
                .andExpect(jsonPath("$.upgradePrice").value(full - match));

        unlock(u, token, "PAWCOIN").andExpect(status().isOk());
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(before - full);
        assertThat(jdbc.queryForObject("SELECT price_idr FROM keepsake_purchases WHERE sku = 'TAILSONALITY' AND ref_id ="
                + " (SELECT id FROM tailsonality_results WHERE public_token = ?)", Long.class, token))
                .isEqualTo(full - match);
        mvc.perform(get(BASE + "/" + token).header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.unlocked").value(true))
                .andExpect(jsonPath("$.matchUnlocked").value(true))
                .andExpect(jsonPath("$.upgradePrice").doesNotExist());

        mvc.perform(post(BASE + "/" + token + "/match-unlock").header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"QRIS\"}"))
                .andExpect(status().isConflict());
    }

    /** 完整解读直接买（原价）→ 配型随之可看；QRIS 配型单走 TS_MATCH 用途，到账置 match_unlocked_at。 */
    @Test
    void fullUnlockIncludesMatchAndQrisMatchGrantsOnPayment() throws Exception {
        User u = userWithPet();
        String a = submit(u);
        String res = mvc.perform(post(BASE + "/" + a + "/match-unlock").header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"QRIS\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unlocked").value(false))
                .andReturn().getResponse().getContentAsString();
        String intent = json.readTree(res).path("payment").path("token").asText();
        assertThat(jdbc.queryForObject("SELECT purpose FROM payment_intents WHERE public_token = ?", String.class,
                intent)).isEqualTo("TS_MATCH");
        paymentIntents.applyCallback(new PaymentCallback(intent, "gw-" + SEQ.incrementAndGet(), GatewayStatus.PAID,
                Map.of()));
        assertThat(jdbc.queryForObject("SELECT match_unlocked_at IS NOT NULL FROM tailsonality_results"
                + " WHERE public_token = ?", Boolean.class, a)).isTrue();

        wallet.credit(u.getId(), 50_000L, PawCoinTxnType.TOPUP, "TEST", null, "ts-topup:" + SEQ.incrementAndGet());
        String b = submit(u);
        unlock(u, b, "PAWCOIN").andExpect(status().isOk());
        mvc.perform(get(BASE + "/" + b).header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.matchUnlocked").value(true));
        assertThat(jdbc.queryForObject("SELECT match_unlocked_at IS NULL FROM tailsonality_results"
                + " WHERE public_token = ?", Boolean.class, b)).as("完整解读不写配型列，靠 unlocked_at 判定").isTrue();
    }
}
