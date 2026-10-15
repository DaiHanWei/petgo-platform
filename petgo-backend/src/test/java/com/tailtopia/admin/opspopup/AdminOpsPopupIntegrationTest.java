package com.tailtopia.admin.opspopup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.ops.popup.domain.OpsPopup;
import com.tailtopia.ops.popup.repository.OpsPopupRepository;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.support.ApiIntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1（需 Postgres + Redis）：后台「运营弹窗」配置页（V1.3.4 AB-30）。
 *
 * <p>核心闭环：后台新建（默认停用）→ 启用 → App 候选接口可见 → 停用 → 不可见；编辑不换 id / token；
 * 抽屉 422 落回表单错误槽；只读账号 403；上传 &gt;1MB 被拒。
 *
 * <p>🔴 图片一律用 https 完整地址：测试环境没配 CDN base，objectKey 在 App 侧换算成 null，
 * 候选接口会把它当「图不可用」过滤掉 —— 那测的就不是启停了。
 */
@Import(AdminOpsPopupIntegrationTest.StubOss.class)
class AdminOpsPopupIntegrationTest extends ApiIntegrationTest {

    /** 假对象存储：本地无阿里云凭证。只替掉「把字节送出去」这一步，大小 / 格式校验走真实代码。 */
    @TestConfiguration
    static class StubOss {
        @Bean
        @Primary
        AliyunOssClient stubOssClient(com.tailtopia.shared.media.MediaProperties props) {
            return new AliyunOssClient(props) {
                @Override
                public String putPublicObject(String objectKey, byte[] bytes, String contentType) {
                    return "https://cdn.test/" + objectKey;
                }
            };
        }
    }

    private static final String BASE = "/admin/ops-popups";
    private static final String CANDIDATES = "/api/v1/ops-popups/candidates";
    private static final String IMG = "https://cdn.example/popup/id.jpg";

    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OpsPopupRepository popupRepo;
    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ops_popups");
    }

    /** STAFF principal 显式声明（不让 hasRole('SUPER_ADMIN') 的隐式全权把权限断言变成恒真）。 */
    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "opsp-" + n + "@tailtopia.test", "运营弹窗测试", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF, Set.of(codes));
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    private Authentication editor() {
        return staffWith(AdminPermissions.CONFIG_VIEW, AdminPermissions.CONFIG_EDIT);
    }

    private static MockHttpServletRequestBuilder htmx(MockHttpServletRequestBuilder b) {
        return b.header("HX-Request", "true").header("HX-Target", "ops-popup-form-err");
    }

    /** 行内启用 / 停用按钮：hx-target 是列表上方的 #ops-popup-row-error（不是抽屉体）。 */
    private static MockHttpServletRequestBuilder rowHtmx(MockHttpServletRequestBuilder b) {
        return b.header("HX-Request", "true").header("HX-Target", "ops-popup-row-error");
    }

    /**
     * 「全部用户 · 每人一次 · 跳 Know Your Pet」的合法表单；{@code overrides} 成对给出「字段, 值」覆盖默认值。
     * ⚠️ 不能在外面再 {@code .param(同名)}：MockMvc 是追加不是覆盖，两个值会被绑成「A,B」。
     */
    private static MockHttpServletRequestBuilder form(MockHttpServletRequestBuilder b, String name,
            String... overrides) {
        Map<String, String> f = new java.util.LinkedHashMap<>();
        f.put("name", name);
        f.put("priority", "3");
        f.put("startsAt", "2020-01-01T00:00");
        f.put("endsAt", "");
        f.put("audience", "ALL");
        f.put("frequency", "ONCE");
        f.put("imageUrlId", IMG);
        f.put("imageUrlEn", "");
        f.put("titleId", "Judul");
        f.put("titleEn", "");
        f.put("bodyId", "Isi");
        f.put("bodyEn", "");
        f.put("buttonTextId", "Lihat");
        f.put("buttonTextEn", "");
        f.put("targetType", "APP_PAGE");
        f.put("targetValue", "KNOW_YOUR_PET");
        for (int i = 0; i + 1 < overrides.length; i += 2) {
            f.put(overrides[i], overrides[i + 1]);
        }
        f.forEach(b::param);
        return b;
    }

    private Map<String, Object> rowByName(String name) {
        return jdbc.queryForMap("SELECT id, public_token, status, title_en, priority FROM ops_popups WHERE name = ?", name);
    }

    private long auditCount(String action, String token) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE action_type = ? AND target_id = ?",
                Long.class, action, token);
    }

    private List<JsonNode> candidates() throws Exception {
        String body = mvc.perform(get(CANDIDATES)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<JsonNode> out = new ArrayList<>();
        json.readTree(body).get("items").forEach(out::add);
        return out;
    }

    @Test
    @DisplayName("新建（默认停用）→ 启用 → 游客候选可见、跳 APP_PAGE/KNOW_YOUR_PET → 停用 → 不可见；四类审计齐全")
    void createActivateDeactivateRoundTrip() throws Exception {
        Authentication ed = editor();
        String name = "opsp-flow-" + SEQ.incrementAndGet();

        // 新建抽屉：复用列表 mapping（?create=1）；两个上传控件限 1MB / 1 张；人群默认「全部」⇒ 天数行隐藏且禁用
        String drawer = mvc.perform(get(BASE).param("create", "1").header("HX-Request", "true")
                        .with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).doesNotContain("<html").contains("f-opsp-name").contains("hx-post=\"" + BASE + "\"")
                .containsPattern("data-show-when=\"audience:NEW,OLD\"[^>]*hidden")
                .containsPattern("<input[^>]*disabled[^>]*name=\"newUserDays\"");
        assertThat(drawer.split("data-max-bytes=\"1048576\"", -1)).hasSize(3);

        MvcResult created = mvc.perform(htmx(form(post(BASE), name)).with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        String body = created.getResponse().getContentAsString();
        assertThat(body).doesNotContain("<html").contains("hx-swap-oob").contains("id=\"ops-popup-rows\"").contains(name);
        assertThat(created.getResponse().getHeader("HX-Trigger")).contains("admin:drawer-close");

        Map<String, Object> row = rowByName(name);
        String token = (String) row.get("public_token");
        assertThat(row.get("status")).isEqualTo("DISABLED");
        assertThat(row.get("title_en")).as("空白串归一为 null").isNull();
        assertThat(auditCount("OPS_POPUP_CREATED", token)).isEqualTo(1);
        assertThat(candidates()).as("新建默认停用，App 看不到").isEmpty();

        // 行内启用：整表 oob + toast，但🔴不关 / 不清抽屉（运营可能正开着另一条在改）
        MvcResult act = mvc.perform(rowHtmx(post(BASE + "/" + token + "/activate")).with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(act.getResponse().getContentAsString()).contains("hx-swap-oob").contains("id=\"ops-popup-rows\"");
        assertThat(act.getResponse().getHeader("HX-Trigger")).as("行内启停不发 drawer-close").isNull();
        List<JsonNode> live = candidates();
        assertThat(live).hasSize(1);
        assertThat(live.get(0).get("token").asText()).isEqualTo(token);
        assertThat(live.get(0).get("target").get("type").asText()).isEqualTo("APP_PAGE");
        assertThat(live.get(0).get("target").get("value").asText()).isEqualTo("KNOW_YOUR_PET");
        assertThat(auditCount("OPS_POPUP_ACTIVATED", token)).isEqualTo(1);

        mvc.perform(post(BASE + "/" + token + "/deactivate").with(authentication(ed)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(candidates()).isEmpty();
        assertThat(rowByName(name).get("status")).isEqualTo("DISABLED");
        assertThat(auditCount("OPS_POPUP_DEACTIVATED", token)).isEqualTo(1);

        // 列表：状态列显示「停用」徽标；URL 里只有 token，没有自增 id
        String list = mvc.perform(get(BASE).param("lang", "zh_CN").with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("ops-popup-row-" + token).contains("停用")
                .contains(BASE + "/" + token + "/activate")
                .contains("id=\"ops-popup-row-error\"").contains("hx-target=\"#ops-popup-row-error\"")
                .doesNotContain("hx-target=\"#ops-popup-drawer-body\"")
                .doesNotContain(BASE + "/" + row.get("id") + "/");
    }

    @Test
    @DisplayName("编辑：原记录更新，id 与 token 不变，状态不被编辑改掉；审计 UPDATED")
    void editKeepsIdAndToken() throws Exception {
        Authentication ed = editor();
        String name = "opsp-edit-" + SEQ.incrementAndGet();
        mvc.perform(form(post(BASE), name).with(authentication(ed)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        Map<String, Object> before = rowByName(name);
        String token = (String) before.get("public_token");
        mvc.perform(post(BASE + "/" + token + "/activate").with(authentication(ed)).with(csrf()));

        // 编辑抽屉回填：提交地址是 update（token），字段带原值
        String drawer = mvc.perform(get(BASE).param("open", token).header("HX-Request", "true")
                        .with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).doesNotContain("<html").contains(BASE + "/" + token).contains("value=\"" + name + "\"")
                .containsPattern("value=\"KNOW_YOUR_PET\"[^>]*selected");

        mvc.perform(htmx(form(post(BASE + "/" + token), name, "priority", "9", "titleEn", "Hello"))
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk());
        Map<String, Object> after = rowByName(name);
        assertThat(after.get("id")).isEqualTo(before.get("id"));
        assertThat(after.get("public_token")).isEqualTo(token);
        assertThat(after.get("priority")).isEqualTo(9);
        assertThat(after.get("title_en")).isEqualTo("Hello");
        assertThat(after.get("status")).as("编辑不改启停").isEqualTo("ACTIVE");
        assertThat(auditCount("OPS_POPUP_UPDATED", token)).isEqualTo(1);
    }

    @Test
    @DisplayName("抽屉提交校验失败 → 422 片段落回表单错误槽（带错误码供标红），不 oob 换表、不落库")
    void validationErrorIsInlineInTheForm() throws Exception {
        String name = "opsp-422-" + SEQ.incrementAndGet();
        MvcResult res = mvc.perform(htmx(form(post(BASE), name, "targetType", "EXTERNAL",
                        "targetValue", "ftp://x.example/a"))
                        .with(authentication(editor())).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(res.getResponse().getHeader("HX-Retarget")).isEqualTo("#ops-popup-form-err");
        assertThat(res.getResponse().getContentAsString())
                .contains("data-code=\"admin.err.opsPopup.targetExternal\"").doesNotContain("hx-swap-oob");

        mvc.perform(htmx(form(post(BASE), name, "name", "")).with(authentication(editor())).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(htmx(form(post(BASE), name, "endsAt", "2019-12-31T00:00"))
                        .with(authentication(editor())).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(htmx(form(post(BASE), name, "buttonTextId", "x".repeat(41)))
                        .with(authentication(editor())).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(htmx(form(post(BASE), name, "targetType", "PRODUCT", "targetValue", "nosuchprod"))
                        .with(authentication(editor())).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_popups WHERE name = ?", Integer.class, name))
                .isZero();
    }

    @Test
    @DisplayName("🔒 只读（仅 config.view）：能看列表与详情，无新建 / 启停 / 保存入口；POST 一律 403")
    void readOnlyAccountCannotWrite() throws Exception {
        Authentication ed = editor();
        String name = "opsp-ro-" + SEQ.incrementAndGet();
        mvc.perform(form(post(BASE), name).with(authentication(ed)).with(csrf()));
        String token = (String) rowByName(name).get("public_token");

        Authentication viewer = staffWith(AdminPermissions.CONFIG_VIEW);
        String page = mvc.perform(get(BASE).with(authentication(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains(name).doesNotContain("ops-popups?create=1\"").doesNotContain("/activate\"");
        String drawer = mvc.perform(get(BASE).param("open", token).header("HX-Request", "true")
                        .with(authentication(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).containsPattern("<fieldset[^>]*disabled").doesNotContain("data-requires-target");

        mvc.perform(form(post(BASE), name + "x").with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(htmx(post(BASE + "/" + token + "/activate")).with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(rowHtmx(post(BASE + "/" + token + "/deactivate")).with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(form(post(BASE + "/" + token), name, "priority", "99").with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(htmx(form(post(BASE + "/" + token), name, "priority", "99")).with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(rowByName(name).get("priority")).as("编辑被拒，优先级未变").isEqualTo(3);
        mvc.perform(multipart(BASE + "/images").file(new MockMultipartFile("file", "a.png", "image/png", png(10, 10)))
                        .with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(rowByName(name).get("status")).isEqualTo("DISABLED");

        // 没有 config.* 的账号连页面都进不去
        mvc.perform(get(BASE).with(authentication(staffWith(AdminPermissions.CONTENT_VIEW))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("上传：>1MB 400 JSON 报错且不上传；正常图返 objectKey（目录 ops-popup）")
    void uploadRejectsOverOneMegabyte() throws Exception {
        byte[] big = new byte[1024 * 1024 + 200 * 1024]; // 1.2MB
        String err = mvc.perform(multipart(BASE + "/images")
                        .file(new MockMultipartFile("file", "big.png", "image/png", big))
                        .param("lang", "zh_CN")
                        .with(authentication(editor())).with(csrf()))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(err).get("error").asText()).contains("1MB");

        String ok = mvc.perform(multipart(BASE + "/images")
                        .file(new MockMultipartFile("file", "p.png", "image/png", png(1080, 1350)))
                        .with(authentication(editor())).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode up = json.readTree(ok);
        assertThat(up.get("objectKey").asText()).contains("/ops-popup/");
        assertThat(up.get("w").asInt()).isEqualTo(1080);
        assertThat(up.path("warning").isMissingNode() || up.path("warning").isNull()).as("不回传信息流裁切预判").isTrue();
    }

    @Test
    @DisplayName("行内启停失败（token 不存在）→ 404 落进列表上方的行内错误槽，不关抽屉")
    void rowToggleErrorLandsInVisibleSlot() throws Exception {
        MvcResult res = mvc.perform(rowHtmx(post(BASE + "/nosuchtoken/activate"))
                        .param("lang", "zh_CN").with(authentication(editor())).with(csrf()))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(res.getResponse().getHeader("HX-Retarget")).isEqualTo("#ops-popup-row-error");
        assertThat(res.getResponse().getHeader("HX-Trigger")).isNull();
        assertThat(res.getResponse().getContentAsString())
                .contains("data-code=\"admin.err.opsPopup.notFound\"").doesNotContain("hx-swap-oob");
    }

    @Test
    @DisplayName("@DynamicUpdate：编辑事务不把装载时的旧状态写回（编辑与启停交错时，启停结果保留）")
    void editDoesNotResurrectStatus() throws Exception {
        Authentication ed = editor();
        String name = "opsp-race-" + SEQ.incrementAndGet();
        mvc.perform(form(post(BASE), name).with(authentication(ed)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        String token = (String) rowByName(name).get("public_token");

        tx.executeWithoutResult(s -> {
            OpsPopup p = popupRepo.findByPublicToken(token).orElseThrow(); // 装载时 DISABLED
            // 模拟另一人此刻点了「启用」（直接改库，实体快照仍是 DISABLED）
            jdbc.update("UPDATE ops_popups SET status = 'ACTIVE' WHERE public_token = ?", token);
            p.update(name + "-v2", p.getStartsAt(), p.getEndsAt(), p.getPriority(), p.getAudience(),
                    p.getNewUserDays(), p.getFrequency(), p.getImageUrlId(), p.getImageUrlEn(), p.getTitleId(),
                    p.getTitleEn(), p.getBodyId(), p.getBodyEn(), p.getButtonTextId(), p.getButtonTextEn(),
                    p.getTargetType(), p.getTargetValue());
        });
        Map<String, Object> after = rowByName(name + "-v2");
        assertThat(after.get("status")).as("编辑只写脏列，不把 DISABLED 写回").isEqualTo("ACTIVE");
    }

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
