package com.tailtopia.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.config.domain.ConfigChangeLog;
import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.repository.ConfigChangeLogRepository;
import com.tailtopia.config.repository.PricingConfigRepository;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.shared.pay.ChargeRequest;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.PaymentGateway;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * L1（需 postgres + redis，云端不跑）：V1.4.0 multi-pet Story 2.2 · AC9 —— 后台「宠物名额月租价格」卡：改价 7,000 → 新单 7,000、
 * 改价前生成的 PENDING 单意图金额仍 5,000；只记真变化（PRICING / pet_slot_monthly_price）+ 一条审计；无 {@code config.edit} 403；
 * 配置页渲染含新卡；越界 flash error 不落库。
 */
class AdminPetSlotPricingIntegrationTest extends ApiIntegrationTest {

    @MockitoBean
    private GrowthQueryService growth;
    @MockitoBean
    private PaymentGateway gateway;

    @Autowired
    private PricingConfigRepository pricingRepo;
    @Autowired
    private ConfigChangeLogRepository changeLogs;
    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;

    private Long saved;

    @BeforeEach
    void setUp() {
        when(growth.getLevel(anyLong())).thenReturn(GrowthLevel.of(1, GrowthTier.NEWBIE));
        when(gateway.createCharge(any(ChargeRequest.class))).thenAnswer(inv -> {
            ChargeRequest r = inv.getArgument(0);
            return new ChargeResult("gw-" + r.orderId(), "QR", Map.of());
        });
        saved = current().getPetSlotMonthlyPrice();
    }

    /** 单行配置表全局共享、测试库不回滚——还原避免污染同一次 run 的其它测试类。 */
    @AfterEach
    void restore() {
        PricingConfig c = current();
        c.setPetSlotMonthlyPrice(saved);
        pricingRepo.saveAndFlush(c);
    }

    private PricingConfig current() {
        return pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow();
    }

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("petslotcfg-" + n + "@tailtopia.test",
                "月租定价超管", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(),
                AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(p, null, new java.util.ArrayList<>(p.getAuthorities()));
    }

    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("petslotcfg-op-" + n + "@tailtopia.test",
                "月租定价运营", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(),
                AdminAccountType.STAFF, java.util.Set.of(codes));
        return new TestingAuthenticationToken(p, null, new java.util.ArrayList<>(p.getAuthorities()));
    }

    private long auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long.class);
    }

    private String qrisRent(long uid, String key) throws Exception {
        return mvc.perform(post("/api/v1/pet-slots/me/rentals").header(HttpHeaders.AUTHORIZATION, userBearer(uid))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1,\"channel\":\"QRIS\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void priceChangeAppliesToNewOrdersOnlyAndIsAudited() throws Exception {
        PricingConfig c = current();
        c.setPetSlotMonthlyPrice(5000);
        pricingRepo.saveAndFlush(c);
        User u = newUser();
        String before = qrisRent(u.getId(), "pre-change");
        long intentAmountBefore = json.readTree(before).get("payment").get("amount").asLong();
        assertThat(intentAmountBefore).isEqualTo(5000L);

        long logsBefore = changeLogs.count();
        long auditsBefore = auditCount();
        mvc.perform(post("/admin/config/pet-slot-pricing").with(authentication(superAdmin())).with(csrf())
                        .param("petSlotMonthlyPrice", "7000"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("toast"));
        assertThat(current().getPetSlotMonthlyPrice()).isEqualTo(7000L);
        assertThat(changeLogs.count() - logsBefore).isEqualTo(1);
        assertThat(changeLogs.findTop100ByOrderByChangedAtDesc().get(0)).satisfies(l -> {
            assertThat(l.getField()).isEqualTo("pet_slot_monthly_price");
            assertThat(l.getConfigType()).isEqualTo(ConfigChangeLog.ConfigType.PRICING);
        });
        assertThat(auditCount() - auditsBefore).isEqualTo(1);

        // App 读价只经 /pet-slots/me；新单按 7,000，改价前的 PENDING 单（同键重放）意图金额仍 5,000
        mvc.perform(get("/api/v1/pet-slots/me").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unitPrice").value(7000));
        String replay = qrisRent(u.getId(), "pre-change");
        assertThat(json.readTree(replay).get("payment").get("amount").asLong()).isEqualTo(5000L);
        String fresh = qrisRent(u.getId(), "post-change");
        assertThat(json.readTree(fresh).get("payment").get("amount").asLong()).isEqualTo(7000L);
        String purchaseToken = json.readTree(before).get("purchaseToken").asString();
        assertThat(jdbc.queryForObject("SELECT unit_price FROM pet_slot_purchases WHERE public_token = ?", Long.class,
                purchaseToken)).isEqualTo(5000L);

        // 无变化再提交 → 不写日志不审计
        mvc.perform(post("/admin/config/pet-slot-pricing").with(authentication(superAdmin())).with(csrf())
                        .param("petSlotMonthlyPrice", "7000"))
                .andExpect(status().is3xxRedirection());
        assertThat(changeLogs.count() - logsBefore).isEqualTo(1);
        assertThat(auditCount() - auditsBefore).isEqualTo(1);
    }

    @Test
    void outOfRangeIsRejectedAndNothingChanges() throws Exception {
        long priceBefore = current().getPetSlotMonthlyPrice();
        mvc.perform(post("/admin/config/pet-slot-pricing").with(authentication(superAdmin())).with(csrf())
                        .param("petSlotMonthlyPrice", "99").param("lang", "zh_CN"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("≥100")));
        mvc.perform(post("/admin/config/pet-slot-pricing").with(authentication(superAdmin())).with(csrf())
                        .param("petSlotMonthlyPrice", "100000001"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
        assertThat(current().getPetSlotMonthlyPrice()).isEqualTo(priceBefore);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.update("UPDATE pricing_config SET pet_slot_monthly_price = 99 WHERE id = 1"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void viewOnlyIs403AndPageRendersNewCard() throws Exception {
        mvc.perform(post("/admin/config/pet-slot-pricing").with(authentication(staffWith(AdminPermissions.CONFIG_VIEW)))
                        .with(csrf()).param("petSlotMonthlyPrice", "6000"))
                .andExpect(status().isForbidden());
        String page = mvc.perform(get("/admin/config").param("lang", "zh_CN")
                        .with(authentication(staffWith(AdminPermissions.CONFIG_VIEW))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"cfg-pet-slot\"").contains("宠物名额月租价格").contains("保存月租价格")
                .contains("name=\"petSlotMonthlyPrice\"");
        assertThat(page.indexOf("cfg-pet-slot")).as("排在客服联系方式卡之前").isLessThan(page.indexOf("cfg-support"));
    }

    @Test
    void htmxSaveReturnsCardFragment() throws Exception {
        String body = mvc.perform(post("/admin/config/pet-slot-pricing").with(authentication(superAdmin())).with(csrf())
                        .header("HX-Request", "true").param("petSlotMonthlyPrice", String.valueOf(saved + 100)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("cfg-pet-slot").contains("admin-toast-host");
    }
}
