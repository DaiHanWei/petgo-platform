package com.tailtopia.admin.virtual.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.admin.virtual.dto.VirtualAccountRow;
import com.tailtopia.growth.service.GrowthDisplayLevelService.DisplayLevel;
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

/** V1.4.0 ① Story 4.4（L0）：「运营发布身份」页虚拟号展示等级列（J1）与抽屉卡（J2）片段直渲。 */
class AdminVirtualDisplayLevelRenderTest {

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
        return engine.process(fragment,
                ctx);
    }

    private static final VirtualAccountRow A = new VirtualAccountRow(20005L, "Mochi Mama", null, false, 3, "CAT", 1L,
            Instant.now());
    private static final List<DisplayLevel> OPTIONS = List.of(new DisplayLevel(1, GrowthTier.NEWBIE),
            new DisplayLevel(7, GrowthTier.BUDDY), new DisplayLevel(12, GrowthTier.GUARDIAN));

    private static Map<String, Object> drawer(DisplayLevel current) {
        Map<String, Object> v = new HashMap<>();
        v.put("a", A);
        v.put("speciesOptions", List.of("CAT", "DOG", "GENERAL"));
        v.put("displayLevel", current);
        v.put("displayLevelOptions", OPTIONS);
        return v;
    }

    private static final String DRAWER = "<div th:replace=\"~{admin/fragments/drawer-virtual-account :: drawer}\"></div>";

    @Test
    void drawerCardWithManagePermissionHasSelectAndNativeConfirm() {
        auth("ROLE_ADMIN", "virtual_account.manage");

        String html = render(DRAWER, drawer(new DisplayLevel(7, GrowthTier.BUDDY)));

        assertThat(html).contains("data-section=\"virtual-species\"").contains("data-section=\"virtual-display-level\"")
                .contains("hx-post=\"/admin/virtual-accounts/20005/display-level\"")
                .contains("data-confirm=\"确认把「{0}」的展示等级改为 Lv{1}？\"").contains("data-confirm-args=\"Mochi Mama\"")
                .contains("data-confirm-input=\"displayLevel\"").contains("Lv12 · Guardian")
                .containsPattern("<option value=\"7\"\\s+selected=\"selected\">").doesNotContain("??");
        assertThat(html.indexOf("virtual-species")).isLessThan(html.indexOf("virtual-display-level"));
    }

    @Test
    void drawerCardWithoutManageShowsCurrentValueAndHint() {
        auth("ROLE_ADMIN", "virtual_account.view");

        String html = render(DRAWER, drawer(null));

        assertThat(html).contains("data-display-level-current").contains("—").contains("需要「管理虚拟账号」权限")
                .doesNotContain("name=\"displayLevel\"");
    }

    @Test
    void listColumnShowsTierDotAndLevelOrDash() {
        auth("ROLE_ADMIN", "virtual_account.view");
        VirtualAccountRow b = new VirtualAccountRow(20006L, "Bobo", null, true, 0, "DOG", null, Instant.now());
        Map<String, Object> v = new HashMap<>();
        v.put("accounts", List.of(A, b));
        v.put("displayLevels", Map.of(20005L, new DisplayLevel(12, GrowthTier.GUARDIAN)));

        String html = render("<div th:replace=\"~{admin/fragments/virtual-accounts-list :: rows(false)}\"></div>", v);

        assertThat(html).contains("展示等级").contains("tier-GUARDIAN").contains("Lv12");
        int bRow = html.indexOf("virtual-row-20006");
        assertThat(html.substring(bRow)).contains("data-display-level").contains("—");
        // 列顺序：物种之后、建号人之前
        assertThat(html.indexOf("账号物种定位")).isLessThan(html.indexOf("展示等级"));
        assertThat(html.indexOf("展示等级")).isLessThan(html.indexOf("建号人"));
    }

    // ------------------------------------------------------------------ P-7 官方号区块（2026-10-19 拍板落点）

    private static final String OFFICIAL = "<div th:replace=\"~{admin/fragments/official-accounts :: rows(false)}\"></div>";

    private static Map<String, Object> officials(DisplayLevel current) {
        Map<String, Object> v = new HashMap<>();
        v.put("officials", List.of(new com.tailtopia.admin.virtual.dto.OfficialAccountRow(9L, "TailUs", null)));
        Map<Long, DisplayLevel> dl = new HashMap<>();
        if (current != null) {
            dl.put(9L, current);
        }
        v.put("officialDisplayLevels", dl);
        v.put("displayLevelOptions", OPTIONS);
        return v;
    }

    @Test
    void officialBlockWithManageHasOnlyDisplayLevelForm() {
        auth("ROLE_ADMIN", "virtual_account.manage");

        String html = render(OFFICIAL, officials(new DisplayLevel(12, GrowthTier.GUARDIAN)));

        assertThat(html).contains("id=\"official-rows\"").contains("id=\"official-row-9\"").contains("TailUs")
                .contains("tier-GUARDIAN").contains("Lv12")
                .contains("hx-post=\"/admin/virtual-accounts/official/9/display-level\"")
                .contains("data-confirm-args=\"TailUs\"").contains("data-confirm-input=\"displayLevel\"")
                .containsPattern("<option value=\"12\"\\s+selected=\"selected\">")
                .doesNotContain("/enabled").doesNotContain("/species").doesNotContain("data-official-display-forbidden")
                .doesNotContain("??");
    }

    @Test
    void officialBlockWithoutManageIsReadOnlyWithHint() {
        auth("ROLE_ADMIN", "virtual_account.view");

        String html = render(OFFICIAL, officials(null));

        assertThat(html).contains("id=\"official-row-9\"").contains("—").doesNotContain("hx-post")
                .doesNotContain("<form").contains("data-official-display-forbidden").contains("需要「管理虚拟账号」权限");
    }

    @Test
    void officialBlockEmptyState() {
        auth("ROLE_ADMIN", "virtual_account.manage");
        Map<String, Object> v = officials(null);
        v.put("officials", List.of());
        assertThat(render(OFFICIAL, v)).contains("暂无官方号").doesNotContain("<table");
    }
}
