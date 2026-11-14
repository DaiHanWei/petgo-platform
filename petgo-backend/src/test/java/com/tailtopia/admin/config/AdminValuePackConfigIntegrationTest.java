package com.tailtopia.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.repository.PricingConfigRepository;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1（需 postgres + redis，云端不跑）：V1.5.0 gift-pack Story 5-1 · AC1.8 / AC2.4 —— 「超值礼包」卡渲染（参考价、停售标签）、保存
 * （改价 / 停售 flash、审计 CONFIG_UPDATE_VALUE_PACK）、只读（总开关灰显）；运营弹窗抽屉停售提示。
 */
class AdminValuePackConfigIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PricingConfigRepository pricingRepo;
    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;

    private long t1;
    private long t2;
    private long t3;
    private boolean enabled;

    private PricingConfig current() {
        return pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow();
    }

    @BeforeEach
    void save() {
        PricingConfig c = current();
        t1 = c.getStarterPackTier1Price();
        t2 = c.getStarterPackTier2Price();
        t3 = c.getStarterPackTier3Price();
        enabled = c.isStarterPackEnabled();
    }

    /** 单行配置表全局共享、测试库不回滚——还原。 */
    @AfterEach
    void restore() {
        PricingConfig c = current();
        c.setStarterPackTier1Price(t1);
        c.setStarterPackTier2Price(t2);
        c.setStarterPackTier3Price(t3);
        c.setStarterPackEnabled(enabled);
        pricingRepo.saveAndFlush(c);
    }

    private Authentication admin(AdminAccountType type, String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("valuepack-" + n + "@tailtopia.test",
                "礼包配置", "{bcrypt}x"));
        AdminUserDetails p = type == AdminAccountType.SUPER_ADMIN
                ? new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), type)
                : new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), type,
                        java.util.Set.of(codes));
        return new TestingAuthenticationToken(p, null, new java.util.ArrayList<>(p.getAuthorities()));
    }

    private long audits(String action) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE action = ?", Long.class, action);
        return n == null ? 0 : n;
    }

    @Test
    void cardRendersSavesAndAudits() throws Exception {
        Authentication su = admin(AdminAccountType.SUPER_ADMIN);
        String page = mvc.perform(get("/admin/config").with(authentication(su)).param("lang", "zh_CN"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("cfg-value-pack").contains("starterPackTier3Price").contains("单买合计 Rp");
        long before = audits("CONFIG_UPDATE_VALUE_PACK");
        mvc.perform(post("/admin/config/value-pack").with(authentication(su)).with(csrf()).param("lang", "zh_CN")
                        .param("starterPackTier1Price", "9000").param("starterPackTier2Price", String.valueOf(t2))
                        .param("starterPackTier3Price", String.valueOf(t3)).param("starterPackEnabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("toast", org.hamcrest.Matchers.containsString("价格已更新")));
        assertThat(current().getStarterPackTier1Price()).isEqualTo(9000L);
        // 不传 starterPackEnabled = 停售
        mvc.perform(post("/admin/config/value-pack").with(authentication(su)).with(csrf()).param("lang", "zh_CN")
                        .param("starterPackTier1Price", "9000").param("starterPackTier2Price", String.valueOf(t2))
                        .param("starterPackTier3Price", String.valueOf(t3)))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("toast", org.hamcrest.Matchers.containsString("已停售")));
        assertThat(current().isStarterPackEnabled()).isFalse();
        assertThat(audits("CONFIG_UPDATE_VALUE_PACK") - before).isEqualTo(2);
        String offPage = mvc.perform(get("/admin/config").with(authentication(su)).param("lang", "zh_CN"))
                .andReturn().getResponse().getContentAsString();
        assertThat(offPage).contains("data-value-pack-off");
        // 运营弹窗新建抽屉：停售时有提示元素
        String drawer = mvc.perform(get("/admin/ops-popups").with(authentication(su)).header("HX-Request", "true")
                        .param("create", "1").param("lang", "zh_CN"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).contains("data-show-when-app-page=\"STARTER_PACK\"");
    }

    @Test
    void readOnlyStaffSeesDisabledSwitchAndCannotSave() throws Exception {
        Authentication viewer = admin(AdminAccountType.STAFF, "config.view");
        String page = mvc.perform(get("/admin/config").with(authentication(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).containsPattern("name=\"starterPackEnabled\"[^>]*disabled");
        mvc.perform(post("/admin/config/value-pack").with(authentication(viewer)).with(csrf())
                        .param("starterPackTier1Price", "9000").param("starterPackTier2Price", "11000")
                        .param("starterPackTier3Price", "13000"))
                .andExpect(status().isForbidden());
    }

    @Test
    void outOfRangeRejected() throws Exception {
        mvc.perform(post("/admin/config/value-pack").with(authentication(admin(AdminAccountType.SUPER_ADMIN)))
                        .with(csrf()).param("lang", "zh_CN").param("starterPackTier1Price", "99")
                        .param("starterPackTier2Price", "11000").param("starterPackTier3Price", "13000")
                        .param("starterPackEnabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("≥100")));
        assertThat(current().getStarterPackTier1Price()).isEqualTo(t1);
    }
}
