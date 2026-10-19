package com.tailtopia.admin.growth.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.growth.service.AdminGrowthUserService.Ledger;
import com.tailtopia.admin.growth.service.AdminGrowthUserService.LedgerRow;
import com.tailtopia.admin.growth.service.AdminGrowthUserService.State;
import com.tailtopia.admin.growth.service.AdminGrowthUserService.Tab;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.shared.i18n.AdminLocaleConfig;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.expression.AbstractSecurityExpressionHandler;
import org.springframework.security.access.expression.SecurityExpressionOperations;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterInvocation;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.extras.springsecurity6.dialect.SpringSecurityDialect;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/**
 * V1.4.0 ① Story 4.2（L0）：用户抽屉「成长值」页签片段直渲（UI I1 / I2 / I6 / I7）。云端跑不了 L1 的整抽屉渲染，
 * 这里至少钉住：地基容器（data-utab-panel / id）、三态分支、无权限置灰不隐藏、明细规则列 / 链接、空态。
 */
class AdminGrowthUserTabRenderTest {

    private static SpringTemplateEngine engine;
    private static GenericWebApplicationContext app;
    private static MockServletContext sc;

    @BeforeAll
    static void setUp() {
        ClassLoaderTemplateResolver files = new ClassLoaderTemplateResolver();
        files.setPrefix("templates/");
        files.setSuffix(".html");
        files.setTemplateMode(TemplateMode.HTML);
        files.setCharacterEncoding("UTF-8");
        files.setOrder(1);
        files.setCheckExistence(true);
        StringTemplateResolver strings = new StringTemplateResolver();
        strings.setTemplateMode(TemplateMode.HTML);
        strings.setOrder(2);
        engine = new SpringTemplateEngine();
        engine.addTemplateResolver(files);
        engine.addTemplateResolver(strings);
        engine.addDialect(new SpringSecurityDialect());
        engine.setTemplateEngineMessageSource(new AdminLocaleConfig().messageSource());
        sc = new MockServletContext();
        app = new GenericWebApplicationContext(sc);
        app.registerBean("adminTime", StubTime.class);
        app.registerBean(WebExpressions.class);
        app.refresh();
        sc.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, app);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** {@code #authorization.expression(...)} 需要的 {@code SecurityExpressionHandler<FilterInvocation>}（生产由安全配置提供）。 */
    public static class WebExpressions extends AbstractSecurityExpressionHandler<FilterInvocation> {
        @Override
        protected SecurityExpressionOperations createSecurityExpressionRoot(Authentication authentication,
                FilterInvocation invocation) {
            return new SecurityExpressionRoot(authentication) {
            };
        }
    }

    /** {@code @adminTime.wib(...)} 的替身。 */
    public static class StubTime {
        public String wib(Instant t) {
            return "10-18 12:00";
        }

        public String shortWib(Instant t) {
            return "10-18 12:00";
        }
    }

    private static void auth(String... authorities) {
        var t = new TestingAuthenticationToken("admin", "n/a", authorities);
        t.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(t);
    }

    private static String render(String fragment, Map<String, Object> vars) {
        MockHttpServletRequest req = new MockHttpServletRequest(sc);
        var exchange = org.thymeleaf.web.servlet.JakartaServletWebApplication.buildApplication(sc)
                .buildExchange(req, new MockHttpServletResponse());
        WebContext ctx = new WebContext(exchange, Locale.SIMPLIFIED_CHINESE, new HashMap<>(vars));
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(app, null));
        return engine.process("<div th:replace=\"~{admin/fragments/drawer-users-growth :: " + fragment + "}\"></div>",
                ctx);
    }

    private static Ledger ledger(List<LedgerRow> rows, boolean everAny) {
        return new Ledger(7L, rows, 0, rows.isEmpty() ? 1 : 2, rows.size(), everAny, null, null, null);
    }

    private static Tab real(String blockedKey, Ledger l) {
        return new Tab(7L, State.REAL, blockedKey != null, blockedKey, 7, GrowthTier.BUDDY, 1420, 8, 180L, null, null,
                l);
    }

    private static Map<String, Object> vars(Tab t) {
        return Map.of("growthTab", t, "growthGrantToken", "tok-1");
    }

    @Test
    void realUserWithAdjustPermissionRendersOverviewEnabledCardAndLedger() {
        auth("ROLE_ADMIN", "user.view", "growth.user_adjust");
        List<LedgerRow> rows = List.of(
                new LedgerRow(3, Instant.now(), "OPS_CAMPAIGN", null, "周年庆 <b>", "Ana", 50, null, null, null, null,
                        null),
                new LedgerRow(2, Instant.now(), "POST_PUBLISHED", "TAKEDOWN", null, null, -5, "CONTENT_POST",
                        "admin.v140.growth.ledger.ref.CONTENT_POST", "501", "/admin/content/501/drawer", "Mochi、Bobo"),
                new LedgerRow(1, Instant.now(), "SPEND_DIGITAL", null, null, null, 10, "VET_CONSULT",
                        "admin.payments.purpose.VET_CONSULT", "pi_tok", "/admin/payments/pi_tok/drawer", null));

        String html = render("panel(true)", vars(real(null, ledger(rows, true))));

        assertThat(html).contains("data-utab-panel=\"growth\"").contains("id=\"user-growth-panel\"")
                .doesNotContain(" hidden");
        assertThat(html).contains("Lv7").contains("1,420").contains("距 Lv8 还差").contains("180");
        assertThat(html).contains("id=\"user-growth-adjust-form\"").contains("/admin/users/7/growth/adjust/confirm")
                .contains("name=\"grantToken\" value=\"tok-1\"").doesNotContain("data-growth-adjust-forbidden")
                .doesNotContain("disabled=\"disabled\"");
        assertThat(html).as("运营 note 转义输出").contains("运营活动 · 周年庆 &lt;b&gt;（Ana）");
        assertThat(html).contains("违规下架扣回").contains("-5").contains("+50");
        assertThat(html).contains("href=\"/admin/content/501/drawer\"").contains("target=\"_blank\"")
                .contains("rel=\"noopener\"").contains("帖子 #501").contains("Mochi、Bobo");
        assertThat(html).contains("href=\"/admin/payments/pi_tok/drawer\"").contains("pi_tok");
        assertThat(html).contains("id=\"user-growth-ledger\"").contains("/admin/users/7/growth/ledger")
                .contains("1 / 2").doesNotContain("??");
    }

    @Test
    void withoutAdjustPermissionCardIsGreyedNotHidden() {
        auth("ROLE_ADMIN", "user.view");

        String html = render("panel(false)", vars(real(null, ledger(List.of(), false))));

        assertThat(html).contains(" hidden").contains("data-growth-adjust").contains("id=\"user-growth-adjust-form\"")
                .contains("disabled=\"disabled\"").contains("data-growth-adjust-forbidden")
                .contains("需要「调整用户 EXP」权限");
        assertThat(html).contains("该用户还没有成长值记录");
    }

    @Test
    void deactivatedUserCardIsGreyedWithReason() {
        auth("ROLE_SUPER_ADMIN");

        String html = render("panel(true)", vars(real("admin.v140.growth.adjust.deactivated", ledger(List.of(), true))));

        assertThat(html).contains("disabled=\"disabled\"").contains("账号已停用，不可调整 EXP")
                .doesNotContain("data-growth-adjust-forbidden").contains("没有符合条件的记录");
    }

    @Test
    void virtualShowsDisplayLevelOnly() {
        auth("ROLE_SUPER_ADMIN");
        Tab t = new Tab(7L, State.VIRTUAL, false, null, 1, null, 0, null, null, 12, GrowthTier.GUARDIAN, null);

        String html = render("panel(true)", vars(t));

        assertThat(html).contains("data-growth-state=\"VIRTUAL\"").contains("Lv12").contains("tier-GUARDIAN")
                .contains("href=\"/admin/virtual-accounts?open=7\"").contains(">运营发布身份</a>")
                .doesNotContain("user-growth-adjust-form").doesNotContain("user-growth-ledger");
    }

    @Test
    void deletedShowsNoticeOnly() {
        auth("ROLE_SUPER_ADMIN");
        Tab t = new Tab(7L, State.DELETED, false, null, 1, GrowthTier.NEWBIE, 0, null, null, null, null, null);

        String html = render("panel(true)", vars(t));

        assertThat(html).contains("账号已注销，成长值数据已清理").doesNotContain("user-growth-adjust-form")
                .doesNotContain("user-growth-ledger");
    }

    @Test
    void ledgerFragmentAloneRendersFilterSelectionAndPager() {
        auth("ROLE_ADMIN", "user.view");
        Ledger l = new Ledger(7L, List.of(new LedgerRow(1, Instant.now(), "LAUNCH_BACKFILL", null, null, null, 300,
                null, null, null, null, null)), 1, 3, 45, true, "LAUNCH_BACKFILL", "2026-10-01", "2026-10-18");

        String html = render("ledger", Map.of("gl", l, "ruleOptions", List.of("DAILY_LOGIN", "LAUNCH_BACKFILL")));

        assertThat(html).contains("id=\"user-growth-ledger\"").contains("上线补算").contains("+300")
                .contains("value=\"LAUNCH_BACKFILL\" selected=\"selected\"").contains("value=\"2026-10-01\"")
                .contains("2 / 3").contains("← 上一页").contains("下一页 →")
                .contains("page=0").contains("page=2").contains("rule=LAUNCH_BACKFILL").doesNotContain("??");
    }

    @Test
    void savedFragmentIsVisiblePanelPlusToast() {
        auth("ROLE_SUPER_ADMIN");
        Map<String, Object> v = new HashMap<>(vars(real(null, ledger(List.of(), false))));
        v.put("toast", "已调整 +50 EXP");

        String html = render("saved", v);

        assertThat(html).contains("id=\"user-growth-panel\"").doesNotContain(" hidden")
                .contains("hx-swap-oob=\"beforeend:#admin-toast-host\"").contains("已调整 +50 EXP")
                .contains("<div id=\"user-drawer-err\" hx-swap-oob=\"innerHTML\"></div>");
    }
}
