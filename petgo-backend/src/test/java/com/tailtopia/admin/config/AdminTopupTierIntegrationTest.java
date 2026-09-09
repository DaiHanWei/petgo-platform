package com.tailtopia.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.repository.AdminAuditLogRepository;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.config.service.AdminConfigService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.config.domain.ConfigChangeLog;
import com.tailtopia.config.domain.PawCoinTopupTier;
import com.tailtopia.config.repository.ConfigChangeLogRepository;
import com.tailtopia.config.repository.PawCoinTopupTierRepository;
import com.tailtopia.pay.service.TopupTierProvider;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1（V1.3.0 Story 6.2 · AB-22A / D-24 / D-25）：新建档位（key / 排序 / 日志 / 审计）、重复金额 422、第 5 个启用 422、停用后再启用达上限 422、
 * {@code DbTopupTierProvider.tiers()} 含新档且不含停用、两线程并发新建只成功一个、「查看已停用」fragment 权限。
 * 🛡 档位表全局共享且无删除端点：本类新建的档位在 {@code @AfterEach} 直接从仓储删掉，别让后续 run 的「默认 4 档」断言变红。
 */
class AdminTopupTierIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PawCoinTopupTierRepository tierRepo;
    @Autowired
    private AdminConfigService write;
    @Autowired
    private TopupTierProvider tierProvider;
    @Autowired
    private ConfigChangeLogRepository changeLogs;
    @Autowired
    private AdminAuditLogRepository audits;
    @Autowired
    private AdminAccountRepository adminAccounts;

    /** 本测试用过的金额：清理按 tier_key（"t" + 金额）删，不依赖运行时收集到的 id（并发 / 异常分支也清得掉，复审 #2）。 */
    private final List<Long> usedAmounts = new ArrayList<>();
    private List<Long> disabledByTest = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : disabledByTest) {
            tierRepo.findById(id).ifPresent(t -> {
                t.setEnabled(true);
                tierRepo.save(t);
            });
        }
        disabledByTest = new ArrayList<>();
        for (Long amount : usedAmounts) {
            tierRepo.findByTierKey("t" + amount).ifPresent(tierRepo::delete);
        }
        usedAmounts.clear();
        // 排序恢复连续（删档位后 1..n 有洞）
        List<PawCoinTopupTier> all = tierRepo.findAllByOrderByAmountIdrAsc();
        for (int i = 0; i < all.size(); i++) {
            all.get(i).setSortOrder(i + 1);
        }
        tierRepo.saveAll(all);
    }

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("tier-" + n + "@tailtopia.test", "档位超管", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("tier-op-" + n + "@tailtopia.test", "档位运营", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), AdminAccountType.STAFF, java.util.Set.of(codes));
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    /**
     * 腾一个启用位并返回一个「已停用」档位：库里已有停用档位（上一轮残留 / 人手停过）就直接复用它（复审 #3），否则停用启用列表末尾一个，测试后恢复。
     * 返回后保证：启用数 ≤ 3、至少 1 个停用。
     */
    private PawCoinTopupTier freeOneSlot() {
        List<PawCoinTopupTier> enabled = tierRepo.findByEnabledTrueOrderBySortOrderAsc();
        while (enabled.size() >= 4) {
            PawCoinTopupTier t = enabled.get(enabled.size() - 1);
            write.setTierEnabled(t.getId(), false, 1L);
            disabledByTest.add(t.getId());
            enabled = tierRepo.findByEnabledTrueOrderBySortOrderAsc();
        }
        return tierRepo.findByEnabledFalseOrderBySortOrderAsc().get(0);
    }

    private long uniqueAmount() {
        long a = 1_000_000L + (SEQ.incrementAndGet() % 900_000L);
        usedAmounts.add(a);
        return a;
    }


    @Test
    void createTierKeysSortsLogsAuditsAndShowsUpForTheApp() throws Exception {
        PawCoinTopupTier off = freeOneSlot();
        long amount = uniqueAmount();
        long logsBefore = changeLogs.count(); // 复审 #1：共享累积库不能用 Top-100 截断查询做差

        mvc.perform(post("/admin/config/tiers").with(authentication(superAdmin())).with(csrf()).param("amountIdr", String.valueOf(amount)))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("toast"));

        PawCoinTopupTier t = tierRepo.findByTierKey("t" + amount).orElseThrow();
        assertThat(t.isEnabled()).isTrue();
        assertThat(t.getAmountIdr()).isEqualTo(amount);
        // sort_order 按全部档位金额升序重排 1..n
        List<PawCoinTopupTier> all = tierRepo.findAllByOrderBySortOrderAsc();
        for (int i = 0; i < all.size(); i++) {
            assertThat(all.get(i).getSortOrder()).isEqualTo(i + 1);
            if (i > 0) {
                assertThat(all.get(i).getAmountIdr()).isGreaterThan(all.get(i - 1).getAmountIdr());
            }
        }
        ConfigChangeLog log = changeLogs.findTop100ByOrderByChangedAtDesc().get(0);
        assertThat(changeLogs.count() - logsBefore).isEqualTo(1);
        assertThat(log.getConfigType()).isEqualTo(ConfigChangeLog.ConfigType.TOPUP_TIER);
        assertThat(log.getField()).isEqualTo("tier.t" + amount + ".created");
        assertThat(log.getNewValue()).isEqualTo(String.valueOf(amount));
        assertThat(audits.findTopByOrderByIdDesc().orElseThrow().getActionType()).isEqualTo(AuditActions.TIER_CREATED);
        // AC4：App 端 tiers() 立即含新档（启用），停用档位不出现；byId 对停用档位拒绝
        assertThat(tierProvider.tiers()).extracting(x -> x.id()).contains("t" + amount).doesNotContain(off.getTierKey());
        assertThatThrownBy(() -> tierProvider.byId(off.getTierKey())).isInstanceOf(AppException.class);
        // 重复金额（含已停用）→ 422 flash error
        mvc.perform(post("/admin/config/tiers").with(authentication(superAdmin())).with(csrf()).param("amountIdr", String.valueOf(amount)).param("lang", "zh_CN"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("相同金额")));
        long disabledAmount = off.getAmountIdr();
        mvc.perform(post("/admin/config/tiers").with(authentication(superAdmin())).with(csrf()).param("amountIdr", String.valueOf(disabledAmount)))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
        // 现在启用中又是 4 个 → 第 5 个 422
        mvc.perform(post("/admin/config/tiers").with(authentication(superAdmin())).with(csrf()).param("amountIdr", String.valueOf(uniqueAmount())).param("lang", "zh_CN"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("4 个启用档位")));
        // 停用的那个再启用（htmx）→ 达上限 422 行内 err
        String err = mvc.perform(post("/admin/config/tiers/" + off.getId() + "/enabled").with(authentication(superAdmin())).with(csrf())
                        .param("enabled", "true").param("lang", "zh_CN").header("HX-Request", "true").header("HX-Target", "tiers-inline-error"))
                .andExpect(status().isUnprocessableEntity()).andExpect(header().string("HX-Retarget", "#tiers-inline-error"))
                .andReturn().getResponse().getContentAsString();
        assertThat(err).contains("inline-error").contains("4 个启用档位");
        assertThat(tierRepo.findById(off.getId()).orElseThrow().isEnabled()).isFalse();
        // 金额 0 / 超上限 / 非整数
        mvc.perform(post("/admin/config/tiers").with(authentication(superAdmin())).with(csrf()).param("amountIdr", "0"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
        mvc.perform(post("/admin/config/tiers").with(authentication(superAdmin())).with(csrf()).param("amountIdr", "100000001").param("lang", "zh_CN"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("100,000,000")));
        mvc.perform(post("/admin/config/tiers").with(authentication(superAdmin())).with(csrf()).param("amountIdr", "12.5"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disabledTableFragmentAndReenableViaHtmx() throws Exception {
        PawCoinTopupTier off = freeOneSlot();
        // 只看：config.view 200 fragment；无权限 403
        String frag = mvc.perform(get("/admin/config/tiers/disabled").param("lang", "zh_CN").with(authentication(staffWith(AdminPermissions.CONFIG_VIEW))).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(frag).contains("id=\"tiers-disabled\"").contains(off.getTierKey()).doesNotContain("/admin/config/tiers/" + off.getId() + "/enabled");
        mvc.perform(get("/admin/config/tiers/disabled").with(authentication(staffWith(AdminPermissions.CONTENT_VIEW))).header("HX-Request", "true"))
                .andExpect(status().isForbidden());
        // 整页：主表只列启用中 + 「查看已停用（1）」链接 + 新建入口
        String page = mvc.perform(get("/admin/config").param("lang", "zh_CN").with(authentication(superAdmin()))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"tiers-enabled\"").containsPattern("查看已停用（\\d+）").contains("/admin/config/tiers/disabled")
                .contains("<summary class=\"btn btn-primary btn-sm\"").contains("name=\"amountIdr\"").contains("id=\"tiers-disabled-link\"");
        assertThat(page.split("data-tier-id=\"" + off.getId() + "\"")).hasSize(1); // 停用行不在主表
        // 再启用（htmx，折叠区已展开 expanded=1）→ 200：主表 oob + 链接 oob + 已停用表 oob + toast；既有「至少保留 1 个启用」护栏不变
        String ok = mvc.perform(post("/admin/config/tiers/" + off.getId() + "/enabled").with(authentication(superAdmin())).with(csrf())
                        .param("enabled", "true").param("expanded", "1").param("lang", "zh_CN").header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ok).contains("id=\"tiers-enabled\" hx-swap-oob=\"true\"").contains("id=\"tiers-disabled-link\" hx-swap-oob=\"true\"")
                .contains("id=\"tiers-disabled\"").contains("已停用档位").contains("data-tier-id=\"" + off.getId() + "\"").doesNotContain("<span></span>");
        assertThat(tierRepo.findById(off.getId()).orElseThrow().isEnabled()).isTrue();
        // 再停用（htmx，折叠区未展开 expanded=0）→ 已停用表只回空占位（保持按需展开语义），链接 N 已更新
        String off2 = mvc.perform(post("/admin/config/tiers/" + off.getId() + "/enabled").with(authentication(superAdmin())).with(csrf())
                        .param("enabled", "false").param("expanded", "0").param("lang", "zh_CN").header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(off2).contains("<div id=\"tiers-disabled\" hx-swap-oob=\"true\"></div>").doesNotContain("已停用档位").containsPattern("查看已停用（\\d+）");
        assertThat(tierRepo.findById(off.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void concurrentCreatesAreSerializedByTheAdvisoryLock() throws Exception {
        freeOneSlot(); // 恰好 1 个空位
        long a = uniqueAmount();
        long b = uniqueAmount();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();
        try {
            for (long amount : new long[] {a, b}) {
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        return write.createTier(amount, 1L);
                    } catch (Exception e) { // 复审 #2：任何异常都收进结果，别让 f.get() 抛 ExecutionException 中断清理
                        return e;
                    }
                }));
            }
            go.countDown();
            int ok = 0;
            int rejected = 0;
            for (Future<Object> f : results) {
                Object r = f.get();
                if (r instanceof PawCoinTopupTier) {
                    ok++;
                } else {
                    assertThat(r).isInstanceOf(AppException.class);
                    rejected++;
                }
            }
            assertThat(ok).isEqualTo(1);
            assertThat(rejected).isEqualTo(1);
            assertThat(tierRepo.countByEnabledTrue()).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
    }
}
