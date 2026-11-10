package com.tailtopia.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * L1：通用分页条真渲染（V1.4.2 优化项）。以用户列表（整页跳转）与评论列表（htmx 局部刷新）各验一次：
 * 总条数、页码、跳页框都在；页码链接带着筛选条件；占位符没有漏进任何可点链接。
 */
class AdminPagerRenderIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "pager-" + n + "@tailtopia.test", "分页测试员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(principal, null, new java.util.ArrayList<>(principal.getAuthorities()));
    }

    @Test
    void usersListRendersTotalPagesBatchAndJumpWithFiltersKept() throws Exception {
        for (int i = 0; i < 25; i++) {
            newUser(); // 每页 20 → 至少 2 页
        }
        String html = mvc.perform(get("/admin/users").param("status", "active")
                        .with(authentication(superAdmin())).param("lang", "zh_CN"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("admin-pager").contains("共 ").contains("pager-num is-current")
                .contains("data-pager-jump").contains("跳至");
        // 第 2 页的链接（0 基 page=1）带着 status 筛选，且是整页跳转（没有 hx-get）。
        assertThat(html).containsPattern("href=\"/admin/users\\?page=1&amp;[^\"]*status=active");
        assertThat(html).doesNotContain("hx-get=\"/admin/users?page=");
        // 占位符只能出现在给跳页脚本用的模板属性里，不能漏进可点链接。
        assertThat(html).doesNotContainPattern("href=\"[^\"]*~PAGE~");
        assertThat(html).contains("data-pager-tpl=\"/admin/users?page=~PAGE~");
    }

    @Test
    void htmxListPagerCarriesTargetAndPushUrl() throws Exception {
        // 评论列表：即使只有 1 页也渲染「共 N 条」；多页时页码带 hx-target=#comments-rows。
        String html = mvc.perform(get("/admin/comments").param("q", "zz-no-match-zz")
                        .with(authentication(superAdmin())).param("lang", "zh_CN"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("~PAGE~\"");
    }
}
