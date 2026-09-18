package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1 集成：C2 复购引擎效果 / C3 销售与毛利 / C4 库存周转（V1.3.0 Story 10.5，模板 C 只读）。
 *
 * <p>本 story 是<b>壳层重构</b>：三个 GET 的路径 / 参数 / 权限一字未改，新增的只是 htmx 分支。
 * 所以这里钉的是四件事 —— ① 整页 200 且带只读标识、② `HX-Request` 返卡区 fragment 而不是整页、
 * ③ <b>三页都没有任何 form / 写端点 / 导出</b>（AC4）、④ 权限门照旧。
 *
 * <p>⚠️ 需真实 PostgreSQL + Redis（{@code ApiIntegrationTest} 无 Testcontainers）。
 */
class AdminShopReportsEndpointIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 100000);

    private Authentication staffWith(String... permissionCodes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "rpt-" + n + "@tailtopia.test", "报表测试账号", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF, Set.of(permissionCodes));
        return new TestingAuthenticationToken(principal, null,
                new ArrayList<>(principal.getAuthorities()));
    }

    private String page(String url, Authentication who) throws Exception {
        return mvc.perform(get(url).with(authentication(who)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ---------- AC4：三页共性 ----------

    /**
     * 🔴 <b>只读报表页上一个 {@code <form>} 都不能有</b>（AC4）。
     *
     * <p>期间 / 窗口切换是**读参数**变化，用的是 {@code method="get"} 的筛选条 ——
     * 它是唯一允许的 form。这里断言的是「没有写端点、没有导出」：
     * 报表页一旦长出一个写入口，运营就会把它当成可以在这里改数的地方，
     * 而报表的数全部是聚合出来的 —— 改不了，也不该改。
     */
    @Test
    @DisplayName("🔴 C2 / C3 / C4 三页只读：无任何 POST 表单、无导出入口，且带只读标识")
    void allThreeReportPagesAreReadOnly() throws Exception {
        Authentication finance = staffWith(AdminPermissions.SHOP_FINANCE_VIEW);
        Authentication ops = staffWith(AdminPermissions.CONFIG_VIEW);

        for (String html : new String[] {
                page("/admin/shop/repurchase-dashboard", ops),
                page("/admin/shop/margin", finance),
                page("/admin/shop/inventory-turnover", finance)}) {
            assertThat(html).as("模板 C 的壳必须给出只读标识 —— 不标的话运营会一直找「这里怎么改」")
                    .contains("data-readonly");
            // ⚠️ 不能直接数 `method="post"`：layout 的顶栏有一个登出表单，那是每一页都有的。
            //    判据取「本页自己有没有写入口」——报表页不该出现任何 hx-post 或配置卡。
            assertThat(html).as("只读报表页不得有任何写入口")
                    .doesNotContain("hx-post").doesNotContain("data-config-card");
            assertThat(html).as("本版本三页都没有导出端点（AC4 明确不新增）").doesNotContain("/export");
            assertThat(html).as("i18n 缺键会渲染成 ??admin.… —— 页面上只是一串乱码，不报错")
                    .doesNotContain("??admin.");
        }
    }

    @Test
    @DisplayName("C2 / C3 / C4 的 HX-Request 都返卡区 fragment，不是整页")
    void allThreeReportPagesReturnFragmentsUnderHtmx() throws Exception {
        record Case(String url, Authentication who, String cardsId) { }
        Case[] cases = {
            new Case("/admin/shop/repurchase-dashboard", staffWith(AdminPermissions.CONFIG_VIEW),
                    "repurchase-cards"),
            new Case("/admin/shop/margin", staffWith(AdminPermissions.SHOP_FINANCE_VIEW),
                    "margin-cards"),
            new Case("/admin/shop/inventory-turnover", staffWith(AdminPermissions.SHOP_FINANCE_VIEW),
                    "turnover-cards"),
        };
        for (Case c : cases) {
            String body = mvc.perform(get(c.url()).header("HX-Request", "true")
                            .with(authentication(c.who())))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).as(c.url() + " 的 htmx 分支返回了整页").doesNotContain("<html");
            assertThat(body).contains(c.cardsId());
            // 🔴 明细也随筛选变：只换卡区的话明细会停在上一个期间，
            //    而两块数字并排摆着，谁也看不出哪块是旧的。
            assertThat(body).as(c.url() + " 的明细没有跟着换 —— 它会停在上一个期间的数")
                    .contains("hx-swap-oob");
        }
    }

    // ---------- AC1 / AC2 / AC3：字段现状原样 + 权限 ----------

    /** AC1：C2 的四个区块字段一个都不能少（它是裁决 A-16 的唯一依据）。 */
    @Test
    @DisplayName("C2 四区块字段现状原样，且 DEP-6 / OQ-42 两条口径提示都在")
    void repurchaseDashboardKeepsEveryBlock() throws Exception {
        String html = page("/admin/shop/repurchase-dashboard", staffWith(AdminPermissions.ORDER_VIEW));

        assertThat(html).contains("repurchase-cards").contains("repurchase-detail");
        // 🔴 三个触发类型都要列出来（含本版本恒为 0 的两个）—— 让「为什么是 0」有地方解释
        assertThat(html).contains("粮量见底").contains("驱虫周期").contains("疫苗周期");
        // ⚠️ 这两条提示是 AC 的一部分：少了它们，「恒为 0」会被读成埋点坏了，
        //    而这一页会被当成「可以据此砍掉复购引擎」的依据 —— 它现在还不是。
        assertThat(html).contains("DEP-6").contains("OQ-42");
    }

    @Test
    @DisplayName("C3 品类下拉走 key（不再把 MAKANAN 直接铺给运营），SPEC-22 四行都在")
    void marginCategoryOptionsAreLocalized() throws Exception {
        String html = page("/admin/shop/margin", staffWith(AdminPermissions.SHOP_FINANCE_VIEW));

        assertThat(html).as("品类下拉改走 admin.v130.shopProducts.category.*").contains("主粮");
        assertThat(html).as("枚举常量名不该出现在页面上（切 ID 后连英文都不是）")
                .doesNotContain(">MAKANAN<").doesNotContain(">OBAT_VITAMIN<");
        // 🔴 SPEC-22 的四行是假设 A-19 的直接读数 —— 删掉就没有地方能发现那个假设不成立了
        assertThat(html).contains("A-19");
    }

    @Test
    @DisplayName("C4「建议动作」列保留：只报滞销而不说怎么办，运营还是不会动")
    void turnoverKeepsTheAdviceColumn() throws Exception {
        String html = page("/admin/shop/inventory-turnover", staffWith(AdminPermissions.SHOP_FINANCE_VIEW));

        assertThat(html).contains("turnover-cards").contains("turnover-detail");
        assertThat(html).as("「建议动作」列表头必须在 —— story 的「必须保留」列点名了它")
                .contains("建议动作");
        assertThat(html).as("缺货损失的口径说明不能删：这个数是服务端能给的近似读数，不是损失金额")
                .contains("PostHog");
    }

    // ---------- 权限门（零变更） ----------

    @Test
    @DisplayName("🔒 C3 / C4 要 shop.finance_view；C2 走 config.view 或 order.view（三页权限码不一致，如实）")
    void permissionsAreUnchanged() throws Exception {
        Authentication ops = staffWith(AdminPermissions.CONFIG_VIEW);

        // 🔒 毛利与周转含成本数字，运营码进不去
        mvc.perform(get("/admin/shop/margin").with(authentication(ops)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/shop/inventory-turnover").with(authentication(ops)))
                .andExpect(status().isForbidden());
        // C2 不含成本数字，走运营码
        mvc.perform(get("/admin/shop/repurchase-dashboard").with(authentication(ops)))
                .andExpect(status().isOk());
        // 反过来：财务码进不去 C2（现状就是两套码，不是笔误）
        mvc.perform(get("/admin/shop/repurchase-dashboard")
                        .with(authentication(staffWith(AdminPermissions.SHOP_FINANCE_VIEW))))
                .andExpect(status().isForbidden());
    }
}
