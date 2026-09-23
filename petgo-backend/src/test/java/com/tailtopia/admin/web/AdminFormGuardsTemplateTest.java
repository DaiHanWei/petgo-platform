package com.tailtopia.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shared.i18n.AdminLocaleConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

/**
 * L0 静态守门（UI 稿 9-7 防呆总则；bug 20260923-540 / 541 / 543 / 547 / 548）。
 * 这些都是「属性写了、行为在 admin-core.js / admin.css」的约定，漏一个属性页面照常渲染、测试照常绿，
 * 只有运营点下去才发现 —— 所以把关键挂钩点钉住。
 */
class AdminFormGuardsTemplateTest {

    private static final Path TPL = Path.of("src", "main", "resources", "templates", "admin");
    private static final Path STATIC = Path.of("src", "main", "resources", "static", "admin");

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
    }

    /** 取包含 marker 的那个 form 开标签到 </form> 的片段。 */
    private static String formContaining(String html, String marker) {
        int at = html.indexOf(marker);
        assertThat(at).as("找不到 " + marker).isGreaterThanOrEqualTo(0);
        int start = html.lastIndexOf("<form", at);
        int end = html.indexOf("</form>", at);
        return html.substring(start, end);
    }

    @Test
    void requiredStarRuleExistsAndVetCreateDialogMarksAllFourFields() throws IOException {
        String css = Files.readString(STATIC.resolve("admin.css"), StandardCharsets.UTF_8);
        assertThat(css).contains("label.field:not(.req-in-text):has(:required) > span:first-child::after")
                .contains("label.field.req::after");
        String vets = read(TPL.resolve("vets.html"));
        String create = formContaining(vets, "th:object=\"${createVetForm}\"");
        Matcher req = Pattern.compile("<label class=\"field req\"").matcher(create);
        int n = 0;
        while (req.find()) {
            n++;
        }
        assertThat(n).as("开户四项必填都要显式标星（兄弟结构，全局 :has 规则够不着）").isEqualTo(4);
        assertThat(create).containsPattern("th:field=\"\\*\\{contactPhone}\"[^>]*required");
    }

    @Test
    void placeFormsUseTagCheckboxesAndCoordPasteHooks() throws IOException {
        for (String f : new String[] {"fragments/drawer-places-create.html", "fragments/drawer-places.html"}) {
            String html = read(TPL.resolve(f));
            assertThat(html).as(f + "：标签改勾选").doesNotContain("<input type=\"text\" name=\"tags\"")
                    .contains("PlaceEditForm).KNOWN_TAGS").contains("type=\"checkbox\" name=\"tags\"")
                    .contains("#{'admin.v130.places.tag.' + ${t}}");
            assertThat(html).as(f + "：坐标整串粘贴拆分挂钩").contains("data-coord=\"lat\"").contains("data-coord=\"lng\"");
        }
        assertThat(read(TPL.resolve("fragments/drawer-places.html"))).contains("th:checked=\"${#lists.contains(d.tags, t)}\"");
        assertThat(read(TPL.resolve("fragments/places-list.html"))).contains("#{'admin.v130.places.tag.' + ${t}}");
    }

    @Test
    void placeAndVetEditFormsDisableSaveUntilChanged() throws IOException {
        String place = formContaining(read(TPL.resolve("fragments/drawer-places.html")), "/admin/places/{id}/edit");
        assertThat(place).contains("data-config-card").containsPattern("data-save[^>]*disabled|disabled[^>]*data-save");
        String vet = formContaining(read(TPL.resolve("fragments/drawer-vets.html")), "th:object=\"${editVetForm}\"");
        assertThat(vet).contains("data-config-card").containsPattern("data-save[^>]*disabled|disabled[^>]*data-save");
    }

    @Test
    void coreScriptCarriesGlobalSubmitGuardAndCoordSplit() throws IOException {
        String js = Files.readString(STATIC.resolve("admin-core.js"), StandardCharsets.UTF_8);
        assertThat(js).contains("htmx:beforeRequest").contains("htmx:afterRequest").contains("is-loading")
                .contains("HX-Redirect").contains("window.tailtopiaConfigCardRefresh")
                .contains("input[data-coord=\"lat\"]").contains("input[data-coord=\"lng\"]");
    }

    @Test
    void auditPageShowsLocalizedActionLabel() throws IOException {
        String html = read(TPL.resolve("audit-logs.html"));
        assertThat(html).contains("#{admin.audit.col.actionLabel}").contains("${@auditActionLabels.label(r.actionType)}");
    }

    /** 发布身份池选项的最小替身（模板只读 userId / nickname / real）。 */
    public record Marker(long userId, String nickname, boolean real) {
    }

    @Test
    void createPlaceFormRendersSixLocalizedTagCheckboxes() {
        ClassLoaderTemplateResolver files = new ClassLoaderTemplateResolver();
        files.setPrefix("templates/");
        files.setSuffix(".html");
        files.setTemplateMode(TemplateMode.HTML);
        files.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(files);
        engine.setTemplateEngineMessageSource(new AdminLocaleConfig().messageSource());
        MockServletContext sc = new MockServletContext();
        var exchange = JakartaServletWebApplication.buildApplication(sc)
                .buildExchange(new MockHttpServletRequest(sc), new MockHttpServletResponse());
        WebContext ctx = new WebContext(exchange, Locale.ENGLISH, Map.of(
                "typeOptions", List.of("CAFE"), "cities", List.of("Jakarta"), "defaultCity", "Jakarta",
                "markers", List.of(new Marker(1L, "TailTopia Official", true)),
                "uploadMaxBytes", 10485760L, "uploadMaxMb", 10L, "uploadMaxRequestBytes", 104857600L));
        String html = engine.process("admin/fragments/drawer-places-create", java.util.Set.of("create-form"), ctx);
        assertThat(Pattern.compile("type=\"checkbox\" name=\"tags\"").matcher(html).results().count()).isEqualTo(6);
        assertThat(html).contains("value=\"PETS_ALLOWED_INSIDE\"").contains("Pets allowed inside").contains("Large dogs welcome")
                .doesNotContain("admin.v130.places.tag.").doesNotContain("??");
    }

    /** {@code @adminTime.wib(...)} 的替身（行片段里有时间列）。 */
    public static class StubTime {
        public String wib(java.time.Instant t) {
            return "-";
        }
    }

    @Test
    void placeListRowShowsLocalizedTagsAndKeepsUnknownOnesVerbatim() {
        ClassLoaderTemplateResolver files = new ClassLoaderTemplateResolver();
        files.setPrefix("templates/");
        files.setSuffix(".html");
        files.setTemplateMode(TemplateMode.HTML);
        files.setCharacterEncoding("UTF-8");
        files.setOrder(1);
        files.setCheckExistence(true);
        org.thymeleaf.templateresolver.StringTemplateResolver strings = new org.thymeleaf.templateresolver.StringTemplateResolver();
        strings.setTemplateMode(TemplateMode.HTML);
        strings.setOrder(2);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.addTemplateResolver(files);
        engine.addTemplateResolver(strings);
        engine.setTemplateEngineMessageSource(new AdminLocaleConfig().messageSource());
        var app = new org.springframework.context.support.GenericApplicationContext();
        app.registerBean("adminTime", StubTime.class);
        app.refresh();
        MockServletContext sc = new MockServletContext();
        var exchange = JakartaServletWebApplication.buildApplication(sc)
                .buildExchange(new MockHttpServletRequest(sc), new MockHttpServletResponse());
        var row = new com.tailtopia.admin.places.dto.PlaceRow(7L, "tok", "Kopi", "CAFE", "Cafe", List.of("PET_MENU", "LEGACY_X"), "Jakarta",
                "Jl. 1", "TailTopia Official", false, 0, 0, 0, 0, 0, com.tailtopia.admin.places.domain.PlaceStatus.ACTIVE, null,
                java.time.Instant.EPOCH);
        WebContext ctx = new WebContext(exchange, Locale.forLanguageTag("id"), new java.util.HashMap<>(Map.of("r", row, "oob", false)));
        ctx.setVariable(org.thymeleaf.spring6.expression.ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new org.thymeleaf.spring6.expression.ThymeleafEvaluationContext(app, null));
        String html = engine.process("<table><tbody><tr th:replace=\"~{admin/fragments/places-list :: row(${r}, ${oob})}\"></tr></tbody></table>", ctx);
        assertThat(html).contains(">Makanan hewan<").contains(">LEGACY_X<").doesNotContain("admin.v130.places.tag.");
        app.close();
    }
}
