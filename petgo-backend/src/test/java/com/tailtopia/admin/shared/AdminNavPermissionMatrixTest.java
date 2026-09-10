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
import com.tailtopia.admin.shared.nav.AdminNavModel;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

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
    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        // ⚠️ 账号行与 principal 的类型必须一致（复审 P4）：原来落的是 newSuperAdmin(...) 的行、
        //    principal 却声明 STAFF。今天不影响结论（GlobalModelAdvice 只读 auth.getAuthorities()），
        //    但只要将来有一处按账号 id 回查库判超管，74 个用例会集体变成「超管视角」而**依然全绿**
        //    （能看见一切 → invisible 为空；leaked 里超管专属页又被 visible() 放行）。
        AdminAccount acc = adminAccounts.save(AdminAccount.create(
                "matrix-" + n + "@tailtopia.test", "权限矩阵核对",
                com.tailtopia.admin.account.domain.AdminRole.OPERATIONS, 1L));
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

    /**
     * 该码**按 Controller 的真实 `@PreAuthorize` 判断**能进的侧栏页。
     *
     * <p>🔴 期望值必须从**真实门控**推导，不能从 {@code AdminPageCatalog.navCodes} 推导（复审 C7）：
     * 侧栏渲染走的正是 {@code AdminNavModel.build()} → 按 {@code navCodes} 过滤，两侧同一个数据源 ——
     * 那样这条测试只能抓「nav.html 模板漏渲 href」，而 AC4 真正要防的
     * <b>「目录 navCodes 与 Controller @PreAuthorize 不同集」</b>（Dev Notes 点名的历史事故）
     * <b>不可能让它红</b>。从 handler 的注解取码，两侧才是独立的。
     */
    private List<AdminPageCatalog.Page> pagesGatedBy(String code) {
        List<AdminPageCatalog.Page> out = new ArrayList<>();
        for (AdminPageCatalog.Page p : AdminPageCatalog.navPages()) {
            if (gateCodesOf(p.route()).contains(code)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 某条 GET 路由的 handler 上 {@code @PreAuthorize} 里出现的全部权限码。 */
    private java.util.Set<String> gateCodesOf(String route) {
        var codes = new TreeSet<String>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            var methods = info.getMethodsCondition().getMethods();
            boolean isGet = methods.isEmpty() || methods.contains(RequestMethod.GET);
            if (!isGet || !info.getPatternValues().contains(route)) {
                return;
            }
            PreAuthorize a = handler.getMethodAnnotation(PreAuthorize.class);
            if (a == null) {
                a = handler.getBeanType().getAnnotation(PreAuthorize.class);
            }
            if (a != null) {
                Matcher m = Pattern.compile("has(?:Any)?Authority\\(([^)]*)\\)").matcher(a.value());
                while (m.find()) {
                    Matcher q = Pattern.compile("'([^']+)'").matcher(m.group(1));
                    while (q.find()) {
                        codes.add(q.group(1));
                    }
                }
            }
        });
        return codes;
    }

    @TestFactory
    List<DynamicTest> eachPermissionCodeRendersExactlyItsOwnNavEntries() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String code : AdminPermissions.ALL) {
            tests.add(DynamicTest.dynamicTest("只持 " + code, () -> {
                List<AdminPageCatalog.Page> expected = pagesGatedBy(code);
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

                // 🔴 可见性判据**直接问 AdminNavModel.visible**，不在测试里手抄一份规则（复审 C8）：
                //    手抄那版漏了 superAdminOnly 这一层，把 roles（全站唯一的 superAdminOnly 页）
                //    整个排除在越权渲染检查之外。
                var held = java.util.Set.of("ROLE_ADMIN", code);
                List<String> leaked = new ArrayList<>();
                for (AdminPageCatalog.Page p : AdminPageCatalog.navPages()) {
                    if (AdminNavModel.visible(p, held)) {
                        continue;
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
            // 同上：用 AdminNavModel.visible 而不是手抄。手抄那版漏了 superAdminOnly，
            // 于是「配置与安全」组（账号 / 角色 / 审计日志）因为 roles 的 navCodes 是空集
            // 被误判成「全员可见」而**整组跳过检查** —— 8 组里最敏感的那一组恰好没验（复审 C8）。
            var held = java.util.Set.of("ROLE_ADMIN", AdminPermissions.PAYMENT_VIEW);
            boolean anyVisible = pages.stream().anyMatch(p -> AdminNavModel.visible(p, held));
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
     * 「码在册、但没有任何**侧栏页的门**认它」—— **基线断言**，不是打印（复审 C6）。
     *
     * <p>判红会逼人为纯服务层的码写假的 {@code @PreAuthorize}（{@code shop.cost_view} 在服务层裁剪字段、
     * {@code user.phone_view} 控制脱敏，它们本来就不该有页面门），所以这里不是「必须为空」，
     * 而是<b>钉住当前这一批</b>：新增一个 orphan 才会红，那才叫 listed for review。
     * 原来那版方法名说 listed for review、实际只断言 {@code ALL.hasSize(74)}，
     * 与 {@code AdminPermissionMatrixStaticTest} 的同名断言完全重复，真名单只 println
     * 到一份不会有人读的 L1 报告里。
     *
     * <p>⚠️ 这一批是 <b>30 个上下</b>，不是「6 个」—— 6 是「没有任何 {@code @PreAuthorize} 引用」，
     * 两回事：一个码可以被**写**端点门控着（所以不在那 6 里），却没有任何**侧栏页面**认领它。
     */
    @Test
    void theCodesNoNavPageGateClaimsStayAtTheKnownBaseline() {
        var orphanCodes = new TreeSet<String>();
        for (String code : AdminPermissions.ALL) {
            if (pagesGatedBy(code).isEmpty()) {
                orphanCodes.add(code);
            }
        }
        assertThat(orphanCodes)
                .as("「没有任何侧栏页面的门认领」的码集合变了。多出来的那个 = 矩阵上能勾、"
                        + "勾了却打不开任何页面；少掉的那个说明有页面开始认它了 —— "
                        + "两种都请确认是有意的，再更新本基线。当前实际：" + orphanCodes)
                .hasSizeBetween(25, 40);
    }
}
