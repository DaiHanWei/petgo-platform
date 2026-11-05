package com.tailtopia.admin.pin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * L0（V1.4.2 Story 6.2 · AC10.1）：顶置管理的权限门 —— 反射 + 源码文本，无 Spring 上下文。
 *
 * <ul>
 *   <li>VIEW / MANAGE 常量逐字未改（不新增权限码）；</li>
 *   <li>G1a / G1b / pick = VIEW，newDrawer / create = MANAGE；</li>
 *   <li>页签、G1b「＋ 新建顶置」、话题抽屉区块的 {@code sec:authorize} 与 VIEW / MANAGE 逐字一致。</li>
 * </ul>
 */
class AdminContentPinControllerTest {

    private static final String VIEW = "hasRole('SUPER_ADMIN') or hasAuthority('content.pin_view')";
    private static final String MANAGE = "hasRole('SUPER_ADMIN') or hasAuthority('content.pin_manage')";

    @Test
    void gateConstantsAreVerbatim() {
        assertThat(AdminContentPinController.VIEW).isEqualTo(VIEW);
        assertThat(AdminContentPinController.MANAGE).isEqualTo(MANAGE);
    }

    @Test
    void everyEndpointCarriesTheRightGate() {
        Map<String, String> gates = new HashMap<>();
        for (Method m : AdminContentPinController.class.getDeclaredMethods()) {
            GetMapping get = m.getAnnotation(GetMapping.class);
            PostMapping post = m.getAnnotation(PostMapping.class);
            if (get == null && post == null) {
                continue;
            }
            PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
            assertThat(pre).as(m.getName() + " 缺 @PreAuthorize").isNotNull();
            String key = get != null ? "GET " + get.value()[0] : "POST " + post.value()[0];
            gates.put(key, pre.value());
        }
        assertThat(gates).containsEntry("GET /admin/content-pins", VIEW)
                .containsEntry("GET /admin/content-pins/topics", VIEW)
                .containsEntry("GET /admin/content-pins/topics/{topicId}", VIEW)
                .containsEntry("GET /admin/content-pins/pick", VIEW)
                .containsEntry("GET /admin/content-pins/{id}/drawer", VIEW)
                .containsEntry("GET /admin/content-pins/new/drawer", MANAGE)
                .containsEntry("POST /admin/content-pins", MANAGE)
                .containsEntry("POST /admin/content-pins/{id}/edit", MANAGE)
                .containsEntry("POST /admin/content-pins/{id}/terminate", MANAGE);
        assertThat(gates).hasSize(9);
    }

    @Test
    void templateGatesMatchVerbatim() throws Exception {
        Path tpl = Path.of("src", "main", "resources", "templates", "admin");
        String tabs = Files.readString(tpl.resolve("fragments/pins-tabs.html"), StandardCharsets.UTF_8);
        String topic = Files.readString(tpl.resolve("content-pins-topic.html"), StandardCharsets.UTF_8);
        String drawer = Files.readString(tpl.resolve("fragments/drawer-topic.html"), StandardCharsets.UTF_8);

        // 两个页签都 = VIEW
        assertThat(tabs.split("sec:authorize=\"" + java.util.regex.Pattern.quote(VIEW) + "\"", -1)).hasSize(3);
        assertThat(tabs).contains("sec:authorize=\"" + VIEW + "\"");
        assertThat(topic).contains("sec:authorize=\"" + MANAGE + "\"");
        // 话题抽屉区块：查看 = VIEW，新建按钮 = MANAGE
        assertThat(drawer).contains("sec:authorize=\"" + VIEW + "\"").contains("sec:authorize=\"" + MANAGE + "\"");
        for (String s : List.of(tabs, topic, drawer)) {
            assertThat(s).doesNotContainPattern("hasAuthority\\('content\\.pin_(?!manage|view)");
        }
    }

    @Test
    void newTopicPagesStayUnderTheContentPinsSidebarItem() throws Exception {
        String src = Files.readString(Path.of("src", "main", "java", "com", "tailtopia", "admin", "pin", "web",
                "AdminContentPinController.java"), StandardCharsets.UTF_8);
        int g1a = src.indexOf("public String topics(");
        int g1b = src.indexOf("public String topic(");
        assertThat(g1a).isPositive();
        assertThat(g1b).isPositive();
        assertThat(src.substring(g1a, src.indexOf("}", src.indexOf("return", g1a))))
                .contains("model.addAttribute(\"active\", \"content-pins\")");
        assertThat(src.substring(g1b, src.indexOf("}", src.indexOf("return", g1b))))
                .contains("model.addAttribute(\"active\", \"content-pins\")");
    }

    // ---------------------------------------------------------------- 复审：行为（mock，无 Spring 上下文）

    private final com.tailtopia.admin.pin.service.AdminContentPinService service =
            org.mockito.Mockito.mock(com.tailtopia.admin.pin.service.AdminContentPinService.class);
    private final com.tailtopia.shared.i18n.Messages msg =
            org.mockito.Mockito.mock(com.tailtopia.shared.i18n.Messages.class);
    private final com.tailtopia.admin.seed.service.AdminSeedImageService images =
            org.mockito.Mockito.mock(com.tailtopia.admin.seed.service.AdminSeedImageService.class);
    private final AdminContentPinController controller = new AdminContentPinController(service, msg, images);

    private static com.tailtopia.admin.service.AdminUserDetails admin() {
        return new com.tailtopia.admin.service.AdminUserDetails(9L, null, "a@tailtopia.test", "{bcrypt}x",
                com.tailtopia.admin.account.domain.AdminAccountType.SUPER_ADMIN);
    }

    @Test
    void promoCreatedInsideTheTopicDrawerKeepsTheAspectWarningInTheToast() {
        org.mockito.Mockito.when(msg.get("admin.flash.pins.saved")).thenReturn("已保存顶置排期");
        org.mockito.Mockito.when(service.resolveSlot("TOPIC_PAGE", 5L)).thenReturn("TOPIC:5");
        org.mockito.Mockito.when(images.upload(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("pin-promo")))
                .thenReturn(new com.tailtopia.admin.seed.dto.UploadedImage("https://cdn/p.jpg", 2000, 500,
                        "比例 4.00，将左右各裁切约 33%", "k", 3L));
        org.mockito.Mockito.when(service.createPromoPin(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq("TOPIC:5"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(77L);
        var model = new org.springframework.ui.ExtendedModelMap();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var file = new org.springframework.mock.web.MockMultipartFile("promoImageFile", "w.jpg", "image/jpeg",
                new byte[] {1, 2, 3});

        String view = controller.create(admin(), "TOPIC_PAGE", 5L, "topic", "PROMO", null, null, file, "Lomba",
                null, "2026-12-01T00:00", "2026-12-02T00:00",
                new com.tailtopia.admin.shared.web.HxRequest(true, "pin-drawer-err", null, null), model, response,
                new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap());

        assertThat(view).isEqualTo("admin/fragments/drawer-content-pin :: createdInTopic");
        assertThat((String) model.getAttribute("toast")).contains("已保存顶置排期").contains("将左右各裁切约 33%");
        assertThat(response.getHeader("HX-Trigger")).contains("admin:drawer-open").contains("/admin/topics/5/drawer");
    }

    @Test
    void promoCreatedInsideTheTopicDrawerWithoutWarningIsJustTheSavedToast() {
        org.mockito.Mockito.when(msg.get("admin.flash.pins.saved")).thenReturn("已保存顶置排期");
        org.mockito.Mockito.when(service.resolveSlot("TOPIC_PAGE", 5L)).thenReturn("TOPIC:5");
        var model = new org.springframework.ui.ExtendedModelMap();

        controller.create(admin(), "TOPIC_PAGE", 5L, "topic", "PROMO", null, "https://cdn/p.jpg", null, "Lomba",
                null, "2026-12-01T00:00", "2026-12-02T00:00",
                new com.tailtopia.admin.shared.web.HxRequest(true, "pin-drawer-err", null, null), model,
                new org.springframework.mock.web.MockHttpServletResponse(),
                new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap());

        assertThat(model.getAttribute("toast")).isEqualTo("已保存顶置排期");
    }

    @Test
    void listUrlOfPinFallsBackToTheTopicListWhenTheTopicIsNoLongerConfigurable() {
        org.mockito.Mockito.when(service.topicIdOfPin(1L)).thenReturn(null);
        org.mockito.Mockito.when(service.topicIdOfPin(2L)).thenReturn(5L);
        org.mockito.Mockito.when(service.topicIdOfPin(3L)).thenReturn(6L);
        org.mockito.Mockito.when(service.pinnableTopic(5L)).thenReturn(java.util.Optional.of(
                new com.tailtopia.admin.topic.service.TopicView(5L, "A", "a", com.tailtopia.topic.domain.TopicType.USER,
                        com.tailtopia.topic.domain.TopicStatus.HIDDEN, null, null, 0, null, null, null, null)));
        org.mockito.Mockito.when(service.pinnableTopic(6L)).thenReturn(java.util.Optional.empty());

        assertThat(controller.listUrlOfPin(1L)).isEqualTo("/admin/content-pins");
        assertThat(controller.listUrlOfPin(2L)).isEqualTo("/admin/content-pins/topics/5");
        assertThat(controller.listUrlOfPin(3L)).as("禁用 / 不存在 → G1a，不跳 404").isEqualTo("/admin/content-pins/topics");
    }
}
