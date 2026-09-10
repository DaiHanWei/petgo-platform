package com.tailtopia.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * L1：**逐个权限码**跑一遍导航渲染（V1.3.0 Story 11.4 · AC4，74 个码）。
 *
 * <h2>与既有 {@code AdminPagesRenderSmokeTest.everySidebarLinkMatchesItsPageGate} 的分工</h2>
 * 那一条是**按页面**遍历：对每个侧栏里有入口的页，比对「入口门的码集」与「持该码时侧栏是否可见」。
 * 它守的是**页面**这一侧 —— 没有入口的页、以及**从来没被任何页面引用的码**都在它的射程之外。
 *
 * <p>本条按**码**遍历，补的正是那两个盲区：
 * <ul>
 *   <li>一个码只授出去、任何页都不认它 → 运营被授予了一个**什么也打不开**的权限，
 *       矩阵上却是一个能勾的复选框。既有那条不会红（它从页面出发，压根遍历不到这个码）。</li>
 *   <li><b>整组不渲染</b>：组内所有页都无权限时，组标题不该单独立在那儿 ——
 *       一个展开是空的分组，比没有这个分组更让人以为是加载失败。</li>
 * </ul>
 *
 * <p>⚠️ 「码没有任何端点门控用到」**不判红**：有些码只管前端显隐或服务层判定
 * （如 {@code shop.cost_view} 在服务层裁剪字段、{@code user.phone_view} 控制脱敏），
 * 判红会逼人写假的 {@code @PreAuthorize}。这里只把它们**列出来**，由
 * {@code scripts/ci/check-admin-permission-consistency.sh} 的 warning 与本条的清单双份留痕。
 */
class AdminNavPermissionMatrixTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;

    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "matrix-" + n + "@tailtopia.test", "权限矩阵核对", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF);
        List<GrantedAuthority> granted = new ArrayList<>();
        // ⚠️ ROLE_ADMIN 不能省：/admin/** 在 URL 层就要求它，少了拿到的是过滤链 403 而不是页面。
        granted.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String c : codes) {
            granted.add(new SimpleGrantedAuthority(c));
        }
        return new TestingAuthenticationToken(principal, null, granted);
    }

    /** 只取 {@code <nav>…</nav>}：概览页正文里也有快捷入口，整页搜索会把它们当成侧栏链接。 */
    private String navFor(Authentication auth) throws Exception {
        String html = mvc.perform(get("/admin/dashboard").param("lang", "zh_CN")
                        .with(authentication(auth)))
                .andReturn().getResponse().getContentAsString();
        int from = html.indexOf("<nav");
        int to = html.indexOf("</nav>");
        return (from >= 0 && to > from) ? html.substring(from, to) : "";
    }

    /** 该码在页面目录里对应的侧栏页（navCodes 命中即可见）。 */
    private static List<AdminPageCatalog.Page> navPagesFor(String code) {
        return AdminPageCatalog.navPages().stream()
                .filter(p -> p.navCodes().contains(code))
                .toList();
    }

    @TestFactory
    List<DynamicTest> eachPermissionCodeRendersExactlyItsOwnNavEntries() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String code : AdminPermissions.ALL) {
            tests.add(DynamicTest.dynamicTest("只持 " + code, () -> {
                List<AdminPageCatalog.Page> expected = navPagesFor(code);
                String nav = navFor(staffWith(code));

                List<String> invisible = new ArrayList<>();
                for (AdminPageCatalog.Page p : expected) {
                    if (!nav.contains("href=\"" + p.route() + "\"")) {
                        invisible.add(p.key() + " (" + p.route() + ")");
                    }
                }
                assertThat(invisible)
                        .as("🔴 持有 " + code + " 却在侧栏看不到这些页 —— 运营只会得出「我没有这个功能」，"
                                + "而 403、日志、报错一概没有，是最难查的一类")
                        .isEmpty();

                List<String> leaked = new ArrayList<>();
                for (AdminPageCatalog.Page p : AdminPageCatalog.navPages()) {
                    if (p.navCodes().isEmpty() || p.superAdminOnly() || p.navCodes().contains(code)) {
                        continue;   // 全员可见 / 超管专属 / 本码可见，都不在本条范围
                    }
                    if (nav.contains("href=\"" + p.route() + "\"")) {
                        leaked.add(p.key() + " (" + p.route() + ")");
                    }
                }
                assertThat(leaked)
                        .as("🔴 只持 " + code + " 却看得见这些页的入口 —— 点进去就是 403")
                        .isEmpty();
            }));
        }
        return tests;
    }

    /**
     * 组内全部无权限 → **整组不渲染**（AC4 第二句）。
     *
     * <p>拿一个只持「订单与资金」组某个码的账号去看：其余组里凡是**全部页面都要码**的，
     * 组标题都不该出现。一个展开是空的分组，比没有这个分组更让人以为是加载失败。
     */
    @Test
    void aGroupWithNoPermittedPageDoesNotRenderAtAll() throws Exception {
        String nav = navFor(staffWith(AdminPermissions.PAYMENT_VIEW));

        List<String> emptyGroupsShown = new ArrayList<>();
        for (AdminPageCatalog.Group g : AdminPageCatalog.GROUPS) {
            List<AdminPageCatalog.Page> pages = AdminPageCatalog.navPages().stream()
                    .filter(p -> p.group().equals(g.key())).toList();
            if (pages.isEmpty()) {
                continue;
            }
            boolean anyVisible = pages.stream().anyMatch(p ->
                    p.navCodes().isEmpty() || p.navCodes().contains(AdminPermissions.PAYMENT_VIEW));
            if (anyVisible) {
                continue;
            }
            // 组里一页都看不见时，组标题（data-nav-group="<key>"）不该出现。
            if (nav.contains("data-nav-group=\"" + g.key() + "\"")) {
                emptyGroupsShown.add(g.key());
            }
        }
        assertThat(emptyGroupsShown)
                .as("🔴 组内一页都无权限时整组不该渲染 —— 展开是空的分组会被当成加载失败")
                .isEmpty();
    }

    /**
     * 「码在册、但没有任何页面认它」的清单（不判红，只留痕）。
     *
     * <p>判红会逼人为纯服务层的码写假的 {@code @PreAuthorize}；但这份名单必须看得见 ——
     * 它是「矩阵上能勾、勾了什么也打不开」的那一批。
     */
    @Test
    void codesNoNavPageClaimsAreListedForReview() {
        var orphanCodes = new TreeSet<String>();
        for (String code : AdminPermissions.ALL) {
            if (navPagesFor(code).isEmpty()) {
                orphanCodes.add(code);
            }
        }
        System.out.println("[11.4 AC4] 没有任何侧栏页面认领的权限码（" + orphanCodes.size() + " 个）："
                + orphanCodes);
        assertThat(AdminPermissions.ALL)
                .as("权限册应为 74 个码（基线 72 + place.manage + comment.virtual_post）")
                .hasSize(74);
    }
}
