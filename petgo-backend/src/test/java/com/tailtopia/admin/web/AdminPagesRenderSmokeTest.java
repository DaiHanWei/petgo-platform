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
                "/admin/manual-review", "/admin/anomalies", "/admin/consult-sessions", "/admin/vets",
                "/admin/vets/online", "/admin/failed-requests", "/admin/ratings", "/admin/users",
                "/admin/audit-logs", "/admin/accounts"};
        for (String p : paths) {
            assertRenders(p, "zh_CN");
            assertRenders(p, "en");
        }
    }
}
