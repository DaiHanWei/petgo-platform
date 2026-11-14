package com.tailtopia.admin.usermgmt.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.shared.time.AdminTime;
import com.tailtopia.admin.usermgmt.dto.AdminUserDetailView;
import com.tailtopia.shared.i18n.AdminLocaleConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

/**
 * L0：V1.5.0 pet-species Story 2.1 · AC1 / AC4 —— 「宠物档案」页签片段真渲染（无 Security 方言：{@code sec:authorize}
 * 不求值，按钮恒出；权限分支由 L1 验）。结构断言（文本 / 属性 / 条数），不做视觉 golden（L2）。
 */
class AdminPetsPanelTemplateTest {

    private static final Path DRAWER = Path.of("src", "main", "resources", "templates", "admin", "fragments",
            "drawer-users.html");

    private static SpringTemplateEngine engine;
    private static GenericApplicationContext app;

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
        engine.setTemplateEngineMessageSource(new AdminLocaleConfig().messageSource());
        app = new GenericApplicationContext();
        app.registerBean("petSpeciesLabels", PetSpeciesLabels.class);
        app.registerBean("adminTime", AdminTime.class);
        app.refresh();
    }

    @AfterAll
    static void tearDown() {
        app.close();
    }

    private static String render(Locale locale, List<AdminUserDetailView.PetRow> pets, Long expanded) {
        MockServletContext sc = new MockServletContext();
        var exchange = JakartaServletWebApplication.buildApplication(sc)
                .buildExchange(new MockHttpServletRequest(sc), new MockHttpServletResponse());
        Map<String, Object> vars = new HashMap<>();
        vars.put("pets", pets);
        vars.put("exp", expanded);
        WebContext ctx = new WebContext(exchange, locale, vars);
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(app, null));
        return engine.process(
                "<div th:replace=\"~{admin/fragments/drawer-users-pets :: panel(true, 42, ${pets}, ${exp})}\"></div>",
                ctx);
    }

    private static List<AdminUserDetailView.PetRow> sample() {
        List<AdminUserDetailView.SpeciesChangeRow> mochiLog = List.of(
                new AdminUserDetailView.SpeciesChangeRow(Instant.parse("2026-12-02T07:20:00Z"), "RABBIT", "HAMSTER",
                        "ADMIN", "Hex", "用户客诉（工单 #8812）"),
                new AdminUserDetailView.SpeciesChangeRow(Instant.parse("2026-11-24T12:05:00Z"), "OTHER", "RABBIT",
                        "USER", null, null));
        return List.of(
                new AdminUserDetailView.PetRow(1205L, "Mochi", "OTHER", null, "FEMALE", LocalDate.of(2025, 9, 12),
                        "HAMSTER", "HAMSTER", LocalDate.of(2027, 2, 22), Instant.parse("2026-11-24T12:05:00Z"), false,
                        mochiLog),
                new AdminUserDetailView.PetRow(1206L, "Oyen", "CAT", "Kucing Kampung", "FEMALE",
                        LocalDate.of(2025, 9, 12), "CAT", "CAT", null, null, false, List.of()),
                new AdminUserDetailView.PetRow(1207L, "Kiko", "OTHER", "Musang Pandan", null, null, "CIVET", "OTHER",
                        null, null, false, List.of(new AdminUserDetailView.SpeciesChangeRow(
                                Instant.parse("2026-11-20T00:00:00Z"), "OTHER", "CIVET", "MIGRATION", null, null))));
    }

    @Test
    void cardsShowSpeciesGroupNextChangeAndButton() {
        String html = render(Locale.SIMPLIFIED_CHINESE, sample(), 1205L);
        assertThat(html).contains("data-utab-panel=\"pets\"").contains("id=\"user-pets-panel\"")
                .doesNotContain(" hidden");
        assertThat(html).contains("🐹").contains("🐱").contains("🐾");
        assertThat(html).contains(">仓鼠<").contains(">猫<").contains("其他·麝香猫");
        assertThat(html).contains("大类：其他").contains("大类：猫");
        assertThat(html).contains("下次可改：2027-02-22").contains("（用户上次修改 2026-11-24）")
                .contains("下次可改：现在可改");
        // 原有信息保留：品种 / 性别 / 生日 / 档案 ID
        assertThat(html).contains("Kucing Kampung").contains("2025-09-12").contains("1207");
        assertThat(html).contains("hx-post=\"/admin/users/42/pets/1205/species/confirm\"")
                .contains("hx-target=\"#user-drawer-err\"").contains("代改类型");
        assertThat(html.split("data-pet-species-change", -1)).hasSize(4);
    }

    @Test
    void changeLogExpandedForChangedPetWithSourcesAndOperators() {
        String html = render(Locale.SIMPLIFIED_CHINESE, sample(), 1205L);
        assertThat(html).contains("类型修改记录 ▾").contains("时间（WIB）").contains("操作人");
        assertThat(html).contains("2026-12-02 14:20").contains("Hex").contains("用户客诉（工单 #8812）")
                .contains(">运营<").contains(">用户<").contains(">迁移<").contains(">系统<");
        assertThat(html).contains("暂无修改记录");
        assertThat(html.split("<details[^>]*open", -1)).as("只展开改过的那只").hasSize(2);
    }

    @Test
    void englishAndIndonesian() {
        assertThat(render(Locale.ENGLISH, sample(), null)).contains("Group: Other").contains("Can change now")
                .contains(" (user last changed 2026-11-24)").contains("Other · Civet").contains("Change type");
        assertThat(render(Locale.forLanguageTag("id"), sample(), null)).contains("Grup: Lainnya")
                .contains("Bisa diubah sekarang").contains("Lainnya · Musang").contains("Ubah jenis");
    }

    @Test
    void drawerReferencesFragmentAndKeepsTabName() throws IOException {
        String drawer = Files.readString(DRAWER, StandardCharsets.UTF_8);
        assertThat(drawer).contains("drawer-users-pets :: panel(false, ${user.id()}, ${user.pets()}, null)")
                .contains("data-utab=\"pets\"");
    }
}
