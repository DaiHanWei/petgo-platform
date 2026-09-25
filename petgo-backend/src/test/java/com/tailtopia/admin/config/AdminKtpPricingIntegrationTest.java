package com.tailtopia.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.tailtopia.admin.audit.repository.AdminAuditLogRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.config.domain.ConfigChangeLog;
import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.repository.ConfigChangeLogRepository;
import com.tailtopia.config.repository.PricingConfigRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import com.tailtopia.support.ApiIntegrationTest;

/**
 * L1（V1.3.0 Story 6.1 · AB-18A）：迁移后两列 ≥1 且 DB CHECK 在位；{@code POST /admin/config/ktp-pricing} 三价各自 diff、
 * 0 / 负数回显错误且不落库、无变化不写日志；定价卡端点不再接受 / 不再改 HD 价；{@code /api/v1/pet-profiles/me/id-cards/pricing}
 * 含两新字段（契约 X-4）。
 */
class AdminKtpPricingIntegrationTest extends ApiIntegrationTest {

    private static final String API = "/api/v1/pet-profiles/me/id-cards/pricing";

    @Autowired
    private PricingConfigRepository pricingRepo;
    @Autowired
    private ConfigChangeLogRepository changeLogs;
    @Autowired
    private AdminAuditLogRepository audits;
    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;

    private long[] saved;

    private void snapshot() {
        if (saved != null) {
            return;
        }
        PricingConfig c = pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow();
        saved = new long[] {c.getIdHdDownloadPrice(), c.getPassportPageUnlockPrice(), c.getPassportBoardingUnlockPrice()};
    }

    /** 单行配置表全局共享、测试库不回滚——还原避免污染同一次 run 的其它测试类。 */
    @AfterEach
    void restore() {
        if (saved == null) {
            return;
        }
        PricingConfig c = pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow();
        c.setIdHdDownloadPrice(saved[0]);
        c.setPassportPageUnlockPrice(saved[1]);
        c.setPassportBoardingUnlockPrice(saved[2]);
        pricingRepo.saveAndFlush(c);
        saved = null;
    }

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("ktpcfg-" + n + "@tailtopia.test", "KTP 定价超管", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(p, null, new java.util.ArrayList<>(p.getAuthorities()));
    }

    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("ktpcfg-op-" + n + "@tailtopia.test", "KTP 定价运营", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), AdminAccountType.STAFF,
                java.util.Set.of(codes));
        return new TestingAuthenticationToken(p, null, new java.util.ArrayList<>(p.getAuthorities()));
    }

    /** 审计仓储不继承 CrudRepository（哈希链只追加），计数走 SQL。 */
    private long auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long.class);
    }

    private PricingConfig current() {
        return pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow();
    }

    /** AC1：迁移后两列存在、≥1；三条 CHECK 在位（手工改库改成 0 会被拒）。 */
    @Test
    void migrationAddedPassportColumnsWithMinOneChecks() {
        PricingConfig c = current();
        assertThat(c.getPassportPageUnlockPrice()).isGreaterThanOrEqualTo(1);
        assertThat(c.getPassportBoardingUnlockPrice()).isGreaterThanOrEqualTo(1);
        assertThat(c.getIdHdDownloadPrice()).isGreaterThanOrEqualTo(1);
        List<String> checks = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'pricing_config'::regclass AND contype = 'c'", String.class);
        assertThat(checks).contains("ck_pricing_passport_page_min", "ck_pricing_passport_boarding_min", "ck_pricing_id_hd_min");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("UPDATE pricing_config SET passport_page_unlock_price = 0 WHERE id = 1"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /** AC3 / AC4：三价独立保存、只记真变化；改价即时反映到 App 下发接口。 */
    @Test
    void savingThreePricesLogsOnlyChangedColumnsAndTakesEffectOnTheApi() throws Exception {
        snapshot();
        PricingConfig c = current();
        long page = c.getPassportPageUnlockPrice() + 1500;
        long boarding = c.getPassportBoardingUnlockPrice() + 2500;
        long logsBefore = changeLogs.count(); // 复审 #1：共享累积库上不能用 Top-100 截断查询做计数差
        long auditsBefore = auditCount();

        mvc.perform(post("/admin/config/ktp-pricing").with(authentication(superAdmin())).with(csrf())
                        .param("idHdDownloadPrice", String.valueOf(c.getIdHdDownloadPrice()))
                        .param("passportPagePrice", String.valueOf(page))
                        .param("passportBoardingPrice", String.valueOf(boarding)))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("toast"));

        PricingConfig after = current();
        assertThat(after.getIdHdDownloadPrice()).isEqualTo(c.getIdHdDownloadPrice()); // 不联动
        assertThat(after.getPassportPageUnlockPrice()).isEqualTo(page);
        assertThat(after.getPassportBoardingUnlockPrice()).isEqualTo(boarding);
        List<ConfigChangeLog> recent = changeLogs.findTop100ByOrderByChangedAtDesc();
        assertThat(changeLogs.count() - logsBefore).isEqualTo(2);
        assertThat(recent.subList(0, 2)).extracting(ConfigChangeLog::getField)
                .containsExactlyInAnyOrder("passport_page_unlock_price", "passport_boarding_unlock_price");
        assertThat(recent.subList(0, 2)).allSatisfy(l -> assertThat(l.getConfigType()).isEqualTo(ConfigChangeLog.ConfigType.PRICING));
        assertThat(auditCount() - auditsBefore).isEqualTo(1);
        assertThat(audits.findTopByOrderByIdDesc().orElseThrow().getActionType()).isEqualTo("CONFIG_UPDATE_PRICING");

        // 契约 X-4：App 端下发接口即时反映新价，旧字段不变
        mvc.perform(get(API).header("Authorization", userBearer(newUser().getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(c.getIdHdDownloadPrice()))
                .andExpect(jsonPath("$.passportPageUnlockPrice").value(page))
                .andExpect(jsonPath("$.passportBoardingUnlockPrice").value(boarding));

        // 无变化再提交一次 → 不写日志不审计
        mvc.perform(post("/admin/config/ktp-pricing").with(authentication(superAdmin())).with(csrf())
                        .param("idHdDownloadPrice", String.valueOf(c.getIdHdDownloadPrice()))
                        .param("passportPagePrice", String.valueOf(page))
                        .param("passportBoardingPrice", String.valueOf(boarding)))
                .andExpect(status().is3xxRedirection());
        assertThat(changeLogs.count() - logsBefore).isEqualTo(2);
        assertThat(auditCount() - auditsBefore).isEqualTo(1);
    }

    /** AC3：任一价 ≤0 → flash error 回显（整页）且三价均不变；非整数在绑定层 400。 */
    @Test
    void zeroOrNegativeOrNonIntegerIsRejectedAndNothingChanges() throws Exception {
        snapshot();
        PricingConfig c = current();
        mvc.perform(post("/admin/config/ktp-pricing").with(authentication(superAdmin())).with(csrf())
                        .param("idHdDownloadPrice", String.valueOf(c.getIdHdDownloadPrice()))
                        .param("passportPagePrice", "0")
                        .param("passportBoardingPrice", String.valueOf(c.getPassportBoardingUnlockPrice())).param("lang", "zh_CN"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("≥1")));
        mvc.perform(post("/admin/config/ktp-pricing").with(authentication(superAdmin())).with(csrf())
                        .param("idHdDownloadPrice", "-5").param("passportPagePrice", "1").param("passportBoardingPrice", "1"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
        mvc.perform(post("/admin/config/ktp-pricing").with(authentication(superAdmin())).with(csrf())
                        .param("idHdDownloadPrice", "1.5").param("passportPagePrice", "1").param("passportBoardingPrice", "1"))
                .andExpect(status().isBadRequest());
        PricingConfig after = current();
        assertThat(after.getIdHdDownloadPrice()).isEqualTo(c.getIdHdDownloadPrice());
        assertThat(after.getPassportPageUnlockPrice()).isEqualTo(c.getPassportPageUnlockPrice());
        assertThat(after.getPassportBoardingUnlockPrice()).isEqualTo(c.getPassportBoardingUnlockPrice());
    }

    /** AC3 权限：config.view 只看不改（403）；config.edit 可改。定价卡端点不再改 HD 价（回归）。 */
    @Test
    void permissionsAndPricingCardRegression() throws Exception {
        snapshot();
        PricingConfig c = current();
        mvc.perform(post("/admin/config/ktp-pricing").with(authentication(staffWith(AdminPermissions.CONFIG_VIEW))).with(csrf())
                        .param("idHdDownloadPrice", "1").param("passportPagePrice", "1").param("passportBoardingPrice", "1"))
                .andExpect(status().isForbidden());
        String page = mvc.perform(get("/admin/config").param("lang", "zh_CN").with(authentication(staffWith(AdminPermissions.CONFIG_VIEW))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // 只读（Story 6.3 模板 D）：卡仍渲染但输入禁用、保存钮固定禁用、注明所缺权限
        assertThat(page).contains("id=\"cfg-ktp\"").containsPattern("id=\"cfg-ktp\"[^>]*data-readonly=\"true\"")
                .containsPattern("name=\"passportPagePrice\"[^>]*disabled").contains("编辑配置");
        String editPage = mvc.perform(get("/admin/config").param("lang", "zh_CN")
                        .with(authentication(staffWith(AdminPermissions.CONFIG_VIEW, AdminPermissions.CONFIG_EDIT))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(editPage).contains("hx-post=\"/admin/config/ktp-pricing\"").contains("name=\"passportPagePrice\"").contains("name=\"passportBoardingPrice\"")
                .contains("data-ref-hint").doesNotContain("name=\"idHdDownloadPrice\" min=\"0\"")
                .doesNotContainPattern("id=\"cfg-ktp\"[^>]*data-readonly");
        // 定价卡：旧客户端多带 idHdDownloadPrice 参数也不再改 HD 价
        mvc.perform(post("/admin/config/pricing").with(authentication(superAdmin())).with(csrf())
                        .param("vetConsultPrice", String.valueOf(c.getVetConsultPrice())).param("vetShareRate", String.valueOf(c.getVetShareRate()))
                        .param("aiUnlockPrice", String.valueOf(c.getAiUnlockPrice())).param("monthlyFreeQuota", String.valueOf(c.getMonthlyFreeQuota()))
                        .param("idHdDownloadPrice", String.valueOf(c.getIdHdDownloadPrice() + 999)))
                .andExpect(status().is3xxRedirection());
        assertThat(current().getIdHdDownloadPrice()).isEqualTo(c.getIdHdDownloadPrice());
    }
}
