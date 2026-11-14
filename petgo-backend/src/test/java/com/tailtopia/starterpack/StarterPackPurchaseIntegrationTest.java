package com.tailtopia.starterpack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.slot.AdminSlotGrantCommand;
import com.tailtopia.profile.slot.PetSlotPurchaseService;
import com.tailtopia.shared.pay.ChargeRequest;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.PaymentGateway;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * L1（需 postgres + redis，云端不跑）：V1.5.0 gift-pack Story 4-2 · AC4.8 —— PawCoin 成功（余额、次数批行、名额、EXP 由监听入账）；
 * 余额不足无单；冷却内 409；未付 QRIS 单 409；过窗 PENDING 不挡新单；同 requestId 重放；名额满无租用档 3 409。
 */
class StarterPackPurchaseIntegrationTest extends ApiIntegrationTest {

    @MockitoBean
    private GrowthQueryService growth;
    @MockitoBean
    private PaymentGateway gateway;

    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private PetSlotPurchaseService slotPurchases;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        when(growth.getLevel(anyLong())).thenReturn(GrowthLevel.of(1, GrowthTier.NEWBIE));
        when(gateway.createCharge(any(ChargeRequest.class))).thenAnswer(inv -> {
            ChargeRequest r = inv.getArgument(0);
            return new ChargeResult("gw-" + r.orderId(), "QR-" + r.orderId(), Map.of());
        });
    }

    private void fund(long uid, long coins) {
        wallet.credit(uid, coins, PawCoinTxnType.TOPUP, "TEST", null, "sp-topup:" + uid + ":" + SEQ.incrementAndGet());
    }

    private ResultActions buy(long uid, int tier, String channel, String requestId) throws Exception {
        return mvc.perform(post("/api/v1/starter-pack/purchases").header(HttpHeaders.AUTHORIZATION, userBearer(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("tier", tier, "channel", channel, "requestId", requestId))));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private void fillSlotsByAdmin(long uid, int n) {
        for (int i = 0; i < n; i++) {
            slotPurchases.grantByAdmin(new AdminSlotGrantCommand(uid, 30, "test", UUID.randomUUID().toString(),
                    adminActorId()));
        }
    }

    @Test
    void pawcoinPurchasePerTierGrantsCreditsSlotAndExp() throws Exception {
        int[][] cases = {{1, 8000, 9, 40}, {2, 11000, 9, 55}, {3, 13000, 10, 65}};
        for (int[] c : cases) {
            User u = newUser();
            fund(u.getId(), 20_000);
            JsonNode r = body(buy(u.getId(), c[0], "PAWCOIN", "r-" + c[0]).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PAID")));
            String token = r.get("purchaseToken").asString();
            assertThat(wallet.balanceOf(u.getId())).isEqualTo(20_000L - c[1]);
            assertThat(count("SELECT count(*) FROM entitlement_credits WHERE user_id = ?", u.getId())).isEqualTo(c[2]);
            assertThat(count("SELECT COALESCE(sum(quantity), 0) FROM entitlement_credits WHERE user_id = ?",
                    u.getId())).isEqualTo(c[0] == 3 ? 14 : 9);
            assertThat(count("SELECT count(*) FROM pet_slot_rentals WHERE user_id = ? AND source = 'PACK_GRANT'",
                    u.getId())).isEqualTo(c[0] == 1 ? 0 : 1);
            assertThat(count("SELECT COALESCE(sum(delta), 0) FROM growth_ledger WHERE user_id = ? AND ref_type = 'STARTER_PACK'",
                    u.getId())).isEqualTo(c[3]);
            mvc.perform(get("/api/v1/me/starter-pack-purchases/" + token)
                    .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"))
                    .andExpect(jsonPath("$.fulfilled").value(true))
                    .andExpect(jsonPath("$.creditsExpireAt").isNotEmpty());
        }
    }

    @Test
    void insufficientBalanceLeavesNoOrder() throws Exception {
        User u = newUser();
        fund(u.getId(), 1_000);
        buy(u.getId(), 1, "PAWCOIN", "r1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("pawcoin-insufficient")));
        assertThat(count("SELECT count(*) FROM starter_pack_purchases WHERE user_id = ?", u.getId())).isZero();
        assertThat(count("SELECT count(*) FROM entitlement_credits WHERE user_id = ?", u.getId())).isZero();
    }

    @Test
    void cooldownBlocksSecondPurchaseAndReplayReturnsSameOrder() throws Exception {
        User u = newUser();
        fund(u.getId(), 40_000);
        String token = body(buy(u.getId(), 1, "PAWCOIN", "r1").andExpect(status().isOk())).get("purchaseToken")
                .asString();
        buy(u.getId(), 1, "PAWCOIN", "r1").andExpect(status().isOk())
                .andExpect(jsonPath("$.purchaseToken").value(token)).andExpect(jsonPath("$.status").value("PAID"));
        buy(u.getId(), 1, "PAWCOIN", "r2").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("starter-pack-cooldown")))
                .andExpect(jsonPath("$.until").isNotEmpty());
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(32_000L);
        mvc.perform(get("/api/v1/starter-pack").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cooldown.daysLeft").value(7));
    }

    @Test
    void openQrisOrderBlocksAndExpiredOneDoesNot() throws Exception {
        User u = newUser();
        fund(u.getId(), 20_000);
        JsonNode q = body(buy(u.getId(), 1, "QRIS", "q1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.qrPayload").isNotEmpty()));
        String pending = q.get("purchaseToken").asString();
        buy(u.getId(), 1, "PAWCOIN", "r2").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("starter-pack-pending")))
                .andExpect(jsonPath("$.purchaseToken").value(pending));
        // 让付款窗过期：过窗 PENDING 就地置 EXPIRED，不挡新单
        jdbc.update("UPDATE starter_pack_purchases SET pay_expires_at = now() - interval '1 minute' WHERE public_token = ?",
                pending);
        buy(u.getId(), 1, "PAWCOIN", "r3").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(jdbc.queryForObject("SELECT status FROM starter_pack_purchases WHERE public_token = ?", String.class,
                pending)).isEqualTo("EXPIRED");
    }

    @Test
    void tier3ExtendsEarliestRentalWhenSlotsFull() throws Exception {
        User u = newUser();
        fund(u.getId(), 20_000);
        // 「名额满且无租用名额」（FULL）当前不可达：名额 = 1 默认 + 至多 2 等级 + 生效租用，无租用时 ≤ 3。
        // FULL → 409 的分支由 StarterPackViewAssemblerTest 的纯计算覆盖；这里验已满 5 个时档 3 延长、不新建名额。
        fillSlotsByAdmin(u.getId(), 4);
        buy(u.getId(), 3, "PAWCOIN", "r1").andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM pet_slot_purchases WHERE user_id = ? AND kind = 'PACK_EXTEND'",
                u.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM pet_slot_rentals WHERE user_id = ? AND source = 'PACK_GRANT'",
                u.getId())).isZero();
    }

    @Test
    void validationRejectsUnknownTierChannelAndBlankRequestId() throws Exception {
        User u = newUser();
        buy(u.getId(), 4, "PAWCOIN", "r1").andExpect(status().isUnprocessableEntity());
        buy(u.getId(), 1, "MIXED", "r1").andExpect(status().isUnprocessableEntity());
        buy(u.getId(), 1, "PAWCOIN", " ").andExpect(status().isUnprocessableEntity());
    }
}
