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
 * L1：全部外化 admin 页渲染冒烟（Story 1.6 AC4/AC8；本次由中英扩到<b>三语</b>）。
 * 以在职超管身份 GET 各页，断言 200、模板成功渲染、且没有把 message key 当文案漏到页面上。
 *
 * <p>⚠️ 光查 Thymeleaf 的 {@code ??key??} 标记是不够的：本项目的 MessageSource 配了
 * {@code useCodeAsDefaultMessage(true)}，缺键<b>不会</b>产生 {@code ??...??}，而是安静地把
 * {@code admin.nav.dashboard} 这样的键名本身渲染成文案。所以这里另外扫「元素文本以 admin./perm./role. 开头」
 * 的形态——那是漏译唯一的外在症状。
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

    /** 漏译的外在症状：元素文本直接以 message key 前缀开头（见类注释）。 */
    private static final java.util.regex.Pattern LEAKED_KEY =
            java.util.regex.Pattern.compile(">\\s*(admin|perm|role)\\.[a-zA-Z0-9_.]+\\s*<");

    private void assertRenders(String path, String lang) throws Exception {
        String html = mvc.perform(get(path).param("lang", lang).with(authentication(superAdminAuth())))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as(path + " (" + lang + ") 缺键标记").doesNotContain("??admin.");
        assertThat(html).as(path + " (" + lang + ") 应有内容").isNotEmpty();

        java.util.regex.Matcher m = LEAKED_KEY.matcher(html);
        assertThat(m.find())
                .as(path + " (" + lang + ") 把 message key 当文案渲染了："
                        + (m.reset().find() ? m.group() : ""))
                .isFalse();
    }

    @Test
    void allExternalizedAdminPagesRenderInEveryLocale() throws Exception {
        String[] paths = {"/admin/dashboard", "/admin/seed-post", "/admin/reports", "/admin/content",
                "/admin/manual-review", "/admin/anomalies", "/admin/consult-sessions", "/admin/vets",
                "/admin/vets/online", "/admin/failed-requests", "/admin/ratings", "/admin/users",
                "/admin/audit-logs", "/admin/accounts"};
        for (String p : paths) {
            for (java.util.Locale locale : com.tailtopia.shared.i18n.AdminLocaleConfig.SUPPORTED_LOCALES) {
                assertRenders(p, locale.toString());
            }
        }
    }
}
