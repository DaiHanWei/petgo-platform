package com.tailtopia.admin.dashboard.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（真库 + 真 Thymeleaf）：看板页与图表 fragment 四条（V1.3.0 Story 3.4 AC7）：
 * 整页 200 含 {@code data-chart}；{@code HX-Request} 只回 fragment；{@code range=99} → 422 行内 err；无登录 302。
 */
class DashboardChartsMvcTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;

    /** 真建账号再取 principal：admin 链每请求经 AdminSessionGuardFilter 复核账号状态 / security_version，手造 id 会被踢回登录页。 */
    private AdminUserDetails staff() {
        long seq = SEQ.incrementAndGet();
        String email = "dash-staff-" + seq + "@tailtopia.test";
        accountService.createAccount(email, "看板员工", AdminRole.CUSTOM, List.of(), 960000L + seq);
        return userDetailsService.loadByEmail(email, false);
    }

    /** 静态资源走内容指纹：`chart.umd.min-<md5>.js`。 */
    private static final Pattern CHART_JS = Pattern.compile("/admin/vendor/chart\\.umd\\.min(-[0-9a-f]{32})?\\.js");
    private static final Pattern CHARTS_JS = Pattern.compile("/admin/admin-charts(-[0-9a-f]{32})?\\.js");

    @Test
    void fullPageEmbedsFiveCardsWithInlineJsonAndSummaryRow() throws Exception {
        AdminUserDetails staff = staff();
        String html = mvc.perform(get("/admin").param("lang", "zh_CN").with(user(staff)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"dashboard-charts\"").contains("data-chart=\"users\"").contains("data-chart=\"pets\"")
                .contains("data-chart=\"content\"").contains("data-chart=\"engagement\"").contains("data-chart=\"payment\"")
                .contains("\"labels\":[").contains("data-range=\"7\"").contains("data-scope-tab=\"REAL\"").contains("data-pay-tab=\"cash\"")
                .containsPattern(CHART_JS).containsPattern(CHARTS_JS).contains("data-readonly")
                .contains("当前时点总量").contains("发布种子内容").contains("PostHog")
                .doesNotContain("cdn.jsdelivr").doesNotContain("cdnjs");
        // 30 天参数落到首屏；非法 range 回默认 7 天而不是 400
        String html30 = mvc.perform(get("/admin/dashboard").param("range", "30").with(user(staff)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html30).contains("data-range=\"30\"");
        String htmlBad = mvc.perform(get("/admin").param("range", "abc").with(user(staff)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(htmlBad).contains("data-range=\"7\"");
    }

    @Test
    void htmxRequestReturnsFragmentOnly() throws Exception {
        AdminUserDetails staff = staff();
        String frag = mvc.perform(get("/admin/charts").param("range", "30").with(user(staff)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(frag).contains("class=\"chart-grid\"").contains("data-range=\"30\"").contains("data-chart=\"engagement\"")
                .doesNotContain("<html").doesNotContain("id=\"dashboard-charts\"");
        // 非 htmx 访问 fragment 路由 → 回整页
        mvc.perform(get("/admin/charts").param("range", "30").with(user(staff)))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void badRangeIsInlineError422() throws Exception {
        String err = mvc.perform(get("/admin/charts").param("range", "99").param("lang", "zh_CN").with(user(staff())).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(err).contains("inline-error").contains("时间范围只支持近 7 天或近 30 天");
    }

    @Test
    void anonymousIsRedirectedToLogin() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/charts").param("range", "7").header("HX-Request", "true")).andExpect(status().is3xxRedirection());
    }
}
