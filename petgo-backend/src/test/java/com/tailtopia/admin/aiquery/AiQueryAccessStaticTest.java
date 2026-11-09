package com.tailtopia.admin.aiquery;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.aiquery.config.AiQueryProperties;
import com.tailtopia.admin.aiquery.domain.AiQueryLanguage;
import com.tailtopia.admin.aiquery.web.AdminAiQueryController;
import com.tailtopia.admin.shared.AdminPageCatalog;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/** L0：ops AI 查数的准入（Story 1.3 AC3）—— 权限码、角色预设、目录、入口门、三语文案、开关默认值。 */
class AiQueryAccessStaticTest {

    @Test
    void aiQueryCodeIsRegisteredButNotPresetInAnyRole() {
        assertThat(AdminPermissions.AI_QUERY).isEqualTo("ai.query");
        assertThat(AdminPermissions.isValid("ai.query")).isTrue();
        for (AdminRole r : AdminRole.values()) {
            assertThat(r.permissionCodes()).as(r + " 不得预授 ai.query").doesNotContain(AdminPermissions.AI_QUERY);
        }
    }

    @Test
    void catalogHasSingleLinkGroupRightAfterOverview() {
        assertThat(AdminPageCatalog.GROUPS.get(0).key()).isEqualTo(AdminPageCatalog.G_OVERVIEW);
        AdminPageCatalog.Group g = AdminPageCatalog.GROUPS.get(1);
        assertThat(g.key()).isEqualTo(AdminPageCatalog.G_AI_QUERY);
        assertThat(g.isSingleLink()).isTrue();
        assertThat(AdminPageCatalog.GROUPS.get(2).isSingleLink()).isFalse();
        var pages = AdminPageCatalog.byGroup().get(g);
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).route()).isEqualTo(AdminAiQueryController.ROUTE);
        assertThat(pages.get(0).navCodes()).containsExactly(AdminPermissions.AI_QUERY);
        assertThat(pages.get(0).viewCodes()).containsExactly(AdminPermissions.AI_QUERY);
    }

    @Test
    void everyEndpointUsesTheSameGate() {
        assertThat(AdminAiQueryController.AUTH).isEqualTo("hasRole('SUPER_ADMIN') or hasAuthority('ai.query')");
        int endpoints = 0;
        for (Method m : AdminAiQueryController.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(GetMapping.class) || m.isAnnotationPresent(PostMapping.class)) {
                endpoints++;
                PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
                assertThat(pre).as(m.getName() + " 缺 @PreAuthorize").isNotNull();
                assertThat(pre.value()).as(m.getName()).isEqualTo(AdminAiQueryController.AUTH);
            }
        }
        assertThat(endpoints).isPositive();
    }

    @Test
    void navTemplateHasNoHandWrittenSecAuthorize() throws Exception {
        String nav = Files.readString(Path.of("src/main/resources/templates/admin/fragments/nav.html"), StandardCharsets.UTF_8)
                .replaceAll("(?s)<!--.*?-->", "");
        assertThat(nav).doesNotContain("sec:authorize");
        assertThat(nav).contains("g.group.singleLink");
        String page = Files.readString(Path.of("src/main/resources/templates/admin/ai-query.html"), StandardCharsets.UTF_8);
        assertThat(page).doesNotContain("sec:authorize");
    }

    @Test
    void labelsExistInAllLocales() throws Exception {
        for (String locale : List.of("zh_CN", "en", "id")) {
            Properties p = new Properties();
            try (InputStream in = getClass().getResourceAsStream("/i18n/messages_" + locale + ".properties")) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (String k : List.of("perm.ai.query", "admin.group.aiQuery", "admin.nav.aiQuery", "admin.page.ai-query",
                    "admin.aiQuery.disabled.heading", "admin.aiQuery.empty.heading", "admin.aiQuery.list.empty",
                    "admin.aiQuery.sample.users.q", "admin.aiQuery.sample.payments.q",
                    "admin.aiQuery.sample.export.q", "admin.aiQuery.sample.logs.q")) {
                assertThat(p.getProperty(k)).as(locale + " 缺 " + k).isNotBlank();
            }
        }
    }

    @Test
    void modeDefaultsToOffAndOnlyExactOnEnables() {
        AiQueryProperties p = new AiQueryProperties();
        assertThat(p.isEnabled()).isFalse();
        p.setMode("ON");
        assertThat(p.isEnabled()).isTrue();
        p.setMode("true");
        assertThat(p.isEnabled()).isFalse();
        p.setMode(null);
        assertThat(p.isEnabled()).isFalse();
    }

    @Test
    void defaultLanguageFollowsAdminLocale() {
        assertThat(AiQueryLanguage.fromLocale(Locale.SIMPLIFIED_CHINESE)).isEqualTo(AiQueryLanguage.ZH);
        assertThat(AiQueryLanguage.fromLocale(Locale.forLanguageTag("id"))).isEqualTo(AiQueryLanguage.ID);
        assertThat(AiQueryLanguage.fromLocale(Locale.ENGLISH)).isEqualTo(AiQueryLanguage.EN);
        assertThat(AiQueryLanguage.fromCode("id")).contains(AiQueryLanguage.ID);
        assertThat(AiQueryLanguage.fromCode("fr")).isEmpty();
    }
}
