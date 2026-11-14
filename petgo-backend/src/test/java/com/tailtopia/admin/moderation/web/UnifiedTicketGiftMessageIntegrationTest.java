package com.tailtopia.admin.moderation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1（需 postgres + redis，云端只写不跑）：V1.5.0 gift-pack Story 1-5 · AC3.5 / AC4 —— 举报后进「祝福语举报」页签、
 * 隐藏后 message_hidden_at 非空且离开待处理、驳回后离开待处理、App 详情随之 message = null。
 */
class UnifiedTicketGiftMessageIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;
    @Autowired
    private GiftCatalogRepository catalog;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private JdbcTemplate jdbc;

    private AdminUserDetails superAdmin() {
        long seq = SEQ.incrementAndGet();
        makeRoomForSuperAdmin();
        accountService.createAccount("gm-super-" + seq + "@tailtopia.test", "超管", AdminRole.SUPER_ADMIN, List.of(),
                960000L + seq);
        return userDetailsService.loadByEmail("gm-super-" + seq + "@tailtopia.test", false);
    }

    private String sendWithMessage(PetProfile pet, GiftCatalogItem g, String message) throws Exception {
        User sender = newUser();
        wallet.credit(sender.getId(), 1_000, PawCoinTxnType.TOPUP, "TEST", null, "gm-topup:" + sender.getId());
        String resp = mvc.perform(post("/api/v1/pets/" + pet.getId() + "/gifts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(sender.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("giftToken", g.getPublicToken(), "quantity", 1,
                                "channel", "PAWCOIN", "requestId", "gm-" + SEQ.incrementAndGet(),
                                "source", "diary_tab", "message", message))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = json.readTree(resp).get("orderToken").asText();
        mvc.perform(post("/api/v1/gift-orders/" + token + "/message-reports")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(newUser().getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reasonType", "INAPPROPRIATE"))))
                .andExpect(status().isNoContent());
        return token;
    }

    private String rows(AdminUserDetails admin, String token) throws Exception {
        long senderId = jdbc.queryForObject("SELECT sender_id FROM gift_orders WHERE public_token = ?", Long.class,
                token);
        return mvc.perform(get("/admin/manual-review").param("tab", "gift").param("q", String.valueOf(senderId))
                        .with(user(admin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private long orderId(String token) {
        return jdbc.queryForObject("SELECT id FROM gift_orders WHERE public_token = ?", Long.class, token);
    }

    @Test
    void hideAndDismissLeavePendingAndHideClearsMessage() throws Exception {
        AdminUserDetails admin = superAdmin();
        long n = SEQ.incrementAndGet();
        GiftCatalogItem g = catalog.save(GiftCatalogItem.create("gm" + n, "Gift" + n, "Hadiah" + n,
                "https://cdn/g.png", 1_000, false, false, false, GiftKind.NORMAL, null, 0));
        User owner = newUser();
        PetProfile pet = petProfiles.save(PetProfile.create(owner.getId(), PetType.CAT, "Mochi", null, null, null,
                null, "GM-" + SEQ.incrementAndGet()));
        String toHide = sendWithMessage(pet, g, "ucapan jelek " + n);
        String toDismiss = sendWithMessage(pet, g, "ucapan biasa " + n);

        // 共享测试库不清表：按被举报账号（送礼人 id）精确检索，免得旧工单挤满第一页（code review #1）
        assertThat(rows(admin, toHide)).contains("review-row-gift-" + orderId(toHide));
        assertThat(rows(admin, toDismiss)).contains("review-row-gift-" + orderId(toDismiss));

        String detail = mvc.perform(get("/admin/manual-review/" + orderId(toHide) + "/detail").param("tab", "gift")
                        .with(user(admin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("ucapan jelek " + n).contains("/admin/gift-messages/" + orderId(toHide) + "/hide");

        mvc.perform(post("/admin/gift-messages/" + orderId(toHide) + "/hide").with(user(admin)).with(csrf())
                        .header("HX-Request", "true"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT message_hidden_at IS NOT NULL FROM gift_orders WHERE id = ?",
                Boolean.class, orderId(toHide))).isTrue();
        mvc.perform(post("/admin/gift-messages/" + orderId(toDismiss) + "/reports/dismiss-all").with(user(admin))
                        .with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk());

        assertThat(rows(admin, toHide)).doesNotContain("review-row-gift-" + orderId(toHide));
        assertThat(rows(admin, toDismiss)).doesNotContain("review-row-gift-" + orderId(toDismiss));

        JsonNode app = json.readTree(mvc.perform(get("/api/v1/gift-orders/" + toHide)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(app.has("message")).isFalse();
        assertThat(app.get("messageHidden").asBoolean()).isTrue();
        assertThat(app.get("canReport").asBoolean()).isFalse();
    }
}
