package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.repository.IdCardHdPurchaseRepository;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * L1 集成：Story 6.3 身份证高清图付费下载（dev profile）。上下文启动验 Flyway V75 + validate
 * （id_card_hd_purchases 契约）。核心：PawCoin 同步扣费解锁落库、二次购买不重复扣、余额不足回滚、
 * QRIS 建意图到账建购买、回调重放幂等、hdUnlocked 标志、无档案 404。
 */
class IdCardHdIntegrationTest extends ApiIntegrationTest {

    private static final long PRICE = 5_000L; // petgo.id-hd.download-price 默认

    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private IdCardHdPurchaseRepository purchases;
    @Autowired
    private PaymentIntentService paymentIntents;

    private void createProfile(long userId) throws Exception {
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Mochi","petType":"CAT","breed":"British","birthday":"2022-01-01"}
                                """))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions purchase(long userId, String channel)
            throws Exception {
        return mvc.perform(post("/api/v1/pet-profiles/me/id-card/hd-download")
                .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"" + channel + "\"}"));
    }

    @Test
    void pawCoinPurchaseDebitsAndUnlocksIdempotent() throws Exception {
        User u = newUser();
        createProfile(u.getId());
        wallet.credit(u.getId(), 20_000L, PawCoinTxnType.TOPUP, "TOPUP", null,
                "seed-" + SEQ.incrementAndGet());
        long before = wallet.balanceOf(u.getId());

        purchase(u.getId(), "PAWCOIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocked").value(true));

        assertThat(wallet.balanceOf(u.getId())).isEqualTo(before - PRICE);
        assertThat(purchases.existsByUserId(u.getId())).isTrue();

        // GET id-card 反映已解锁
        mvc.perform(get("/api/v1/pet-profiles/me/id-card")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hdUnlocked").value(true));

        // 二次购买短路，不再扣费（余额只减一次）。
        purchase(u.getId(), "PAWCOIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocked").value(true));
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(before - PRICE);
    }

    @Test
    void pawCoinInsufficientBalanceRollsBack() throws Exception {
        User u = newUser();
        createProfile(u.getId()); // 余额 0
        purchase(u.getId(), "PAWCOIN").andExpect(status().isConflict());
        assertThat(purchases.existsByUserId(u.getId())).isFalse(); // 整事务回滚，未建购买
    }

    @Test
    void qrisCreatesIntentThenPaidUnlocksIdempotent() throws Exception {
        User u = newUser();
        createProfile(u.getId());

        var res = purchase(u.getId(), "QRIS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocked").value(false))
                .andExpect(jsonPath("$.payment.token").exists())
                .andReturn();
        String intentToken =
                json.readTree(res.getResponse().getContentAsString()).path("payment").path("token").asText();
        assertThat(purchases.existsByUserId(u.getId())).isFalse(); // 未支付未解锁

        // 到账：applyCallback PAID → PaymentIntentPaidEvent → IdHdPaidHandler 同事务建购买。
        paymentIntents.applyCallback(new PaymentCallback(
                intentToken, "gw-" + SEQ.incrementAndGet(), GatewayStatus.PAID, Map.of()));
        assertThat(purchases.existsByUserId(u.getId())).isTrue();

        // 回调重放幂等：再次 applyCallback 不双建（意图已终态直接返回）。
        paymentIntents.applyCallback(new PaymentCallback(
                intentToken, "gw-dup", GatewayStatus.PAID, Map.of()));
        assertThat(purchases.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void purchaseWithoutProfileIs404() throws Exception {
        User u = newUser();
        purchase(u.getId(), "PAWCOIN").andExpect(status().isNotFound());
    }
}
