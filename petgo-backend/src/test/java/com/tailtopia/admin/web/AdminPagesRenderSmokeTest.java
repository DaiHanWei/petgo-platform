package com.tailtopia.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1：全部外化 admin 页渲染冒烟（Story 1.6 AC4/AC8）。以在职超管身份 GET 各页，断言 200、
 * 模板成功渲染（无 Thymeleaf 缺键标记 {@code ??...??}），中英两 locale 各跑一遍。覆盖 1.6 外化的全部页。
 */
class AdminPagesRenderSmokeTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;

    private Authentication superAdminAuth() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "render-" + n + "@tailtopia.test", "渲染冒烟超管", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(principal, null,
                new java.util.ArrayList<>(principal.getAuthorities()));
    }

    private void assertRenders(String path, String lang) throws Exception {
        String html = mvc.perform(get(path).param("lang", lang).with(authentication(superAdminAuth())))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as(path + " (" + lang + ") 缺键标记").doesNotContain("??admin.");
        assertThat(html).as(path + " (" + lang + ") 应有内容").isNotEmpty();
    }

    /**
     * 「内容」分组的侧栏次序由**产品指定**（2026-08-20）：
     * 种子内容发布 → 内容管理 → 评论管理 → 人工复核 → 用户 → 被举报用户。
     *
     * <p>钉住它是因为这个次序<b>没有任何自解释的规律</b>（不是字母序、不是权限分组序），
     * 后来人加菜单时很容易按「新加的往后排」或顺手重排，而改错了没有任何报错 ——
     * 只有运营下次找不到入口时才会发现。被举报用户排末位是因为它是账号级处置
     * （警告/封号），与前面几项的内容维度不同。
     */
    @Test
    void contentNavKeepsTheProductSpecifiedOrder() throws Exception {
        String html = visibleText("/admin/dashboard");
        java.util.List<String> expected = java.util.List.of(
                "/admin/seed-post", "/admin/content", "/admin/comments",
                "/admin/manual-review", "/admin/users", "/admin/tickets");
        java.util.List<Integer> positions = expected.stream().map(html::indexOf).toList();
        assertThat(positions).as("每个入口都应渲染出来").doesNotContain(-1);
        assertThat(positions).as("侧栏次序：" + String.join(" → ", expected))
                .isSorted();
    }

    /**
     * 侧栏「人工复核」链接的可见性必须与该页入口门一致（2026-08-20 一并放宽到 content.takedown）。
     *
     * <p>两边走散时的表现最难查：**权限放行了、直接敲 URL 能进，但侧栏里没有这个链接** ——
     * 运营只会得出「我没有这个功能」，而日志、403、报错一概没有，无从下手。
     *
     * <p>这里以只持 {@code content.takedown} 的 STAFF 身份渲染任意一页，断言侧栏里有这个链接。
     */
    @Test
    void navShowsManualReviewForTakedownOnlyStaff() throws Exception {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "navstaff-" + n + "@tailtopia.test", "只有下架权的审核员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF);
        // ⚠️ ROLE_ADMIN 不能省：/admin/** 那条链在 URL 层就要求它（SecurityConfig
        //    anyRequest().hasRole("ADMIN")），少了它拿到的是过滤链的 403，
        //    根本走不到 @PreAuthorize —— 会被误读成「权限门没放行」。
        Authentication staff = new TestingAuthenticationToken(principal, null,
                java.util.List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("content.takedown")));

        String html = mvc.perform(get("/admin/manual-review").param("lang", "zh_CN")
                        .with(authentication(staff)))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("只持 content.takedown 应能打开本页（入口门 2026-08-20 放宽）")
                .isNotEmpty();
        assertThat(html).as("侧栏应有「人工复核」入口，否则运营只会以为自己没有这个功能")
                .contains("/admin/manual-review");
    }

    /**
     * 页内标题必须与导航名一致（bug 20260820）。
     *
     * <p>2026-08-19 那次拆分改了导航名（工单队列 → 被举报用户），却漏了页内 h1、
     * 浏览器 title、副标题和权限显示名 —— 侧栏写「被举报用户」、点进去大标题还是
     * 「工单队列」，副标题还写着「三类工单统一排队」，而这页早就只剩一类了。
     *
     * <p>此前没有任何测试盯着这种不一致：缺键会被 {@code ??admin.} 断言抓到，
     * <b>但「键在、值过期」渲染得完全正常</b>。这条直接钉住渲染出的字面量。
     */
    @Test
    void pageHeadingsMatchTheirNavLabels() throws Exception {
        String tickets = visibleText("/admin/tickets");
        assertThat(tickets).as("被举报用户页的标题").contains("被举报用户");
        assertThat(tickets).as("拆分后本页只剩用户举报一类，旧标题不该再出现")
                .doesNotContain("工单队列").doesNotContain("三类工单统一排队");

        assertThat(visibleText("/admin/manual-review")).as("人工复核页的标题").contains("人工复核");

        // Story 11.1：侧栏叫「顶置管理」，页内标题必须同名（四处同源里的前两处）。
        assertThat(visibleText("/admin/content-pins")).as("顶置管理页的标题").contains("顶置管理");
        // Story 11.2：侧栏叫「装饰标签」，页内标题必须同名。
        assertThat(visibleText("/admin/content-tags")).as("装饰标签页的标题").contains("装饰标签");
        // Story 11.3：侧栏叫「用户标签」，页内标题必须同名。
        assertThat(visibleText("/admin/user-tags")).as("用户标签页的标题").contains("用户标签");
    }

    /**
     * 页面渲染结果**去掉 HTML 注释**后的内容。
     *
     * <p>后台模板里的中文说明注释会原样发到浏览器（Thymeleaf 不吃标准 HTML 注释），
     * 其中不乏「从『工单队列』拆分而来」这类如实记述历史的句子 ——
     * 直接对整份 HTML 断言「不含旧名」会被这些注释误伤。这里只留可见文本。
     */
    private String visibleText(String path) throws Exception {
        String html = mvc.perform(get(path).param("lang", "zh_CN")
                        .with(authentication(superAdminAuth())))
                .andReturn().getResponse().getContentAsString();
        return html.replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * 筛选栏的下拉「选完即刷新」（bug 20260820）：靠 {@code form[data-autosubmit]} +
     * admin.js 的 change 委托实现。
     *
     * <p>这条守的是**属性别被顺手删掉** —— 删了页面照样渲染、测试照样绿，
     * 只是运营又得多点一次「筛选」，而这种回退没人会立刻注意到。
     * （JS 行为本身后台没有测试基建，这里只钉住服务端渲染出的那半边。）
     */
    @Test
    void filterBarsOptInToAutoSubmit() throws Exception {
        for (String path : new String[] {"/admin/manual-review", "/admin/tickets"}) {
            String html = mvc.perform(get(path).with(authentication(superAdminAuth())))
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).as(path + " 的筛选表单应带 data-autosubmit").contains("data-autosubmit");
        }
    }

    @Test
    void allExternalizedAdminPagesRenderInBothLocales() throws Exception {
        String[] paths = {"/admin/dashboard", "/admin/seed-post", "/admin/tickets", "/admin/content",
                "/admin/content-pins", "/admin/content-tags", "/admin/user-tags",
                "/admin/manual-review", "/admin/anomalies", "/admin/consult-sessions", "/admin/vets",
                "/admin/vets/online", "/admin/failed-requests", "/admin/ratings", "/admin/users",
                "/admin/audit-logs", "/admin/accounts",
                // V1.1.6 Story 12.1：「运营发布身份」页（虚拟账号 + 运营真实账号两区）。
                // ⚠️ 这一页此前不在本表里 —— 加进来才会验它的 i18n 键在两种语言下都齐。
                "/admin/virtual-accounts"};
        for (String p : paths) {
            assertRenders(p, "zh_CN");
            assertRenders(p, "en");
        }
    }
}
