package com.tailtopia.admin.topic.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.shared.AdminPageCatalog;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * L0（V1.4.2 Story 6.1 · AC1.4）：话题管理的权限门 —— 反射 + 源码文本，无 Spring 上下文。
 *
 * <ul>
 *   <li>VIEW / MANAGE 常量逐字固定；</li>
 *   <li>每个 POST 是 MANAGE；GET 是 VIEW，例外只有新建抽屉与查重（MANAGE）；</li>
 *   <li>侧栏 navCodes == VIEW 门里的码；模板里所有写按钮的 sec 判定与 MANAGE 逐字一致。</li>
 * </ul>
 */
class AdminTopicControllerTest {

    private static final String VIEW = "hasRole('SUPER_ADMIN') or hasAuthority('content.topic_view')";
    private static final String MANAGE = "hasRole('SUPER_ADMIN') or hasAuthority('content.topic_manage')";

    private static final Set<String> MANAGE_GETS = Set.of("/admin/topics/new/drawer", "/admin/topics/name-check");

    @Test
    void gateConstantsAreVerbatim() {
        assertThat(AdminTopicController.VIEW).isEqualTo(VIEW);
        assertThat(AdminTopicController.MANAGE).isEqualTo(MANAGE);
    }

    @Test
    void everyEndpointCarriesTheRightGate() {
        List<String> seen = new ArrayList<>();
        for (Method m : AdminTopicController.class.getDeclaredMethods()) {
            PostMapping post = m.getAnnotation(PostMapping.class);
            GetMapping get = m.getAnnotation(GetMapping.class);
            if (post == null && get == null) {
                continue;
            }
            PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
            assertThat(pre).as(m.getName() + " 缺 @PreAuthorize").isNotNull();
            if (post != null) {
                assertThat(pre.value()).as("POST " + Arrays.toString(post.value())).isEqualTo(MANAGE);
                seen.add("POST " + post.value()[0]);
            } else {
                String path = get.value()[0];
                assertThat(pre.value()).as("GET " + path).isEqualTo(MANAGE_GETS.contains(path) ? MANAGE : VIEW);
                seen.add("GET " + path);
            }
        }
        assertThat(seen).containsExactlyInAnyOrder(
                "GET /admin/topics", "GET /admin/topics/new/drawer", "GET /admin/topics/name-check",
                "GET /admin/topics/{id}/drawer",
                "POST /admin/topics", "POST /admin/topics/{id}/edit", "POST /admin/topics/{id}/hide",
                "POST /admin/topics/{id}/disable", "POST /admin/topics/{id}/restore",
                "POST /admin/topics/{id}/featured", "POST /admin/topics/{id}/featured-order");
    }

    @Test
    void sidebarNavCodesEqualThePageGate() {
        AdminPageCatalog.Page page = AdminPageCatalog.navPages().stream()
                .filter(p -> "topics".equals(p.key())).findFirst().orElseThrow();
        assertThat(page.route()).isEqualTo("/admin/topics");
        assertThat(page.activeKey()).isEqualTo("topics");
        assertThat(page.navKey()).isEqualTo("admin.nav.topics");
        assertThat(page.navCodes()).containsExactly("content.topic_view");
        assertThat(page.viewCodes()).containsExactly("content.topic_view");
        assertThat(page.editCodes()).containsExactly("content.topic_manage");
    }

    @Test
    void topicsComeRightAfterContentTagsInTheCatalog() {
        List<String> keys = AdminPageCatalog.navPages().stream().map(AdminPageCatalog.Page::key).toList();
        assertThat(keys.indexOf("topics")).isEqualTo(keys.indexOf("content-tags") + 1);
    }

    /** 模板里写控件的 sec 判定只能是 MANAGE 原文（Thymeleaf 里单引号写成两个）。 */
    @Test
    void templateWriteGatesMatchManageVerbatim() throws Exception {
        Path tpl = Path.of("src", "main", "resources", "templates", "admin");
        String page = Files.readString(tpl.resolve("topics.html"), StandardCharsets.UTF_8);
        String list = Files.readString(tpl.resolve("fragments/topics-list.html"), StandardCharsets.UTF_8);
        String drawer = Files.readString(tpl.resolve("fragments/drawer-topic.html"), StandardCharsets.UTF_8);
        String escaped = MANAGE.replace("'", "''");

        assertThat(page).contains("sec:authorize=\"" + MANAGE + "\"");
        assertThat(list).contains("#authorization.expression('" + escaped + "')");
        assertThat(drawer).contains("#authorization.expression('" + escaped + "')");
        for (String s : List.of(page, list, drawer)) {
            assertThat(s).doesNotContain("content.topic_view')\"") // 写控件不得挂 VIEW
                    .doesNotContainPattern("hasAuthority\\('content\\.topic_(?!manage|view)");
        }
    }
}
