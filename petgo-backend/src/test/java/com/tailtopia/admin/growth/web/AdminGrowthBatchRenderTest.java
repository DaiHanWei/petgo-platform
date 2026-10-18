package com.tailtopia.admin.growth.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.growth.service.AdminGrowthBatchService.Preview;
import com.tailtopia.admin.growth.service.AdminGrowthBatchService.Problem;
import com.tailtopia.admin.growth.service.AdminGrowthBatchService.Result;
import com.tailtopia.admin.growth.web.AdminGrowthBatchController.Failed;
import com.tailtopia.shared.i18n.AdminLocaleConfig;
import java.util.List;
import java.time.Instant;
import java.util.HashMap;
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

/** V1.4.0 ① Story 4.3（L0）：批量发放抽屉四个片段直渲（UI H1 ~ H5）。 */
class AdminGrowthBatchRenderTest {

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
        return engine.process("<div th:replace=\"~{admin/fragments/drawer-growth-batch :: " + fragment + "}\"></div>",
                ctx);
    }

    private static final String BATCH = "GB-202610181207-ABC123";

    private static Map<String, Object> fill(Map<String, String> fieldErrors, String formError) {
        Map<String, Object> v = new HashMap<>();
        v.put("bf", new Failed(null, "周年庆", "50", "1\n2", null));
        v.put("fieldErrors", fieldErrors);
        v.put("formError", formError);
        v.put("maxIds", 5000);
        return v;
    }

    @Test
    void fillRendersMultipartFormWithStepOneAndFieldErrors() {
        auth("ROLE_SUPER_ADMIN");
        String html = render("fill", fill(Map.of("expPerUser", "每人 EXP 须为正整数"), null));

        assertThat(html).contains("hx-post=\"/admin/growth/batch/preview\"").contains("hx-encoding=\"multipart/form-data\"")
                .contains("step--on").contains("1 · 填写").contains("value=\"周年庆\"").contains("name=\"file\"")
                .contains("accept=\".csv,text/csv\"").contains("/admin/growth/batch/template.csv")
                .contains("data-field-error=\"expPerUser\"").contains("每人 EXP 须为正整数")
                .contains("id=\"growthbatch-err\"").contains("data-drawer-close").doesNotContain("??");
        String general = render("fill", fill(Map.of(), "「运营活动」规则已停用"));
        assertThat(general).contains("banner err").contains("「运营活动」规则已停用");
    }

    @Test
    void previewShowsCountsProblemsAndCarriesNormalizedIds() {
        auth("ROLE_SUPER_ADMIN");
        Preview pv = new Preview(BATCH, "周年庆", 50, List.of(1L, 2L, 3L, 4L, 5L), 2, 1,
                List.of(new Problem("9", "NOT_FOUND"), new Problem("abc", "MALFORMED"), new Problem("7", "VIRTUAL")),
                3, "1,2,3,4,5,9,7,abc");

        String html = render("preview", Map.of("pv", pv));

        assertThat(html).contains("2 · 预览").contains("data-batch-valid").contains(">5<").contains(">250<")
                .contains(BATCH).contains("用户不存在").contains("格式不正确").contains("虚拟号，自动排除")
                .contains("另有 3 条未显示").contains("value=\"1,2,3,4,5,9,7,abc\"")
                .contains("/admin/growth/batch/confirm").contains("/admin/growth/batch/form")
                .doesNotContain("disabled=\"disabled\"").doesNotContain("??");
        Preview none = new Preview(BATCH, "x", 1, List.of(), 1, 0, List.of(new Problem("9", "NOT_FOUND")), 0, "9");
        assertThat(render("preview", Map.of("pv", none))).contains("disabled=\"disabled\"");
    }

    @Test
    void resultShowsGrantedRetryOnFailureAndToast() {
        auth("ROLE_SUPER_ADMIN");
        Map<String, Object> v = new HashMap<>();
        v.put("rs", new Result(BATCH, "周年庆", 50, 5, 0, 1, 1, 1, "Ana"));
        v.put("retry", new Failed(BATCH, "周年庆", "50", "1,2", "6"));
        v.put("toast", "已发放 250 EXP（5 位用户）");

        String html = render("result", v);

        assertThat(html).contains("3 · 发放").contains("已发放：批次 " + BATCH + "，5 位用户各 50 EXP。")
                .contains("2（无效 1 · 虚拟号 / 官方号 1）").contains("Ana").contains("data-batch-failed")
                .contains("失败 1 人").contains("data-batch-retry").contains("value=\"" + BATCH + "\"")
                .contains("hx-swap-oob=\"beforeend:#admin-toast-host\"").doesNotContain("data-batch-replayed");
    }

    @Test
    void replayOnlyResultSaysAlreadyGrantedWithoutToast() {
        auth("ROLE_SUPER_ADMIN");
        Map<String, Object> v = new HashMap<>();
        v.put("rs", new Result(BATCH, "x", 50, 0, 5, 0, 0, 0, "Ana"));
        v.put("retry", new Failed(BATCH, "x", "50", "1", "1"));

        String html = render("result", v);

        assertThat(html).contains("data-batch-replayed").contains("该批次已发放过，未重复发放")
                .doesNotContain("admin-toast-host").doesNotContain("data-batch-retry");
    }

    @Test
    void failedPageOffersBackAndRetryWithSameBatchNo() {
        auth("ROLE_SUPER_ADMIN");
        String html = render("failed", Map.of("fb", new Failed(BATCH, "x", "5", "1,2", "2")));

        assertThat(html).contains("发放失败：服务暂时不可用").contains(BATCH).contains("data-batch-retry")
                .contains("/admin/growth/batch/form").contains("← 上一步");
    }
}
