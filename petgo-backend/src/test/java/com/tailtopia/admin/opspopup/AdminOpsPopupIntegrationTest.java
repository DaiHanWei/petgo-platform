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
 * <p>核心闭环（按后台返工清单 §3）：后台新建（直接生效）→ App 候选接口可见 → 服务端确认弹层停用 → 不可见 → 启用；
 * 只有未开始可删；列表状态筛选 / 名称搜索 / 四格汇总；抽屉 422 一次回全部字段错误；编辑不换 id / token；无权限 403；上传 &gt;1MB 被拒。
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
        return staffWith(AdminPermissions.OPS_POPUP_MANAGE);
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
        f.put("endsAt", "2099-12-31T00:00");
        f.put("audience", "ALL");
        f.put("frequency", "ONCE");
        f.put("imageUrlId", IMG);
        f.put("imageUrlEn", "");
        f.put("titleId", "Judul");
        f.put("titleEn", "");
        f.put("bodyId", "Isi");
        f.put("bodyEn", "");
        f.put("buttonTextId", "Lihat");
        f.put("buttonTextEn", "See");
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

    /** 确认弹层表单的 htmx 请求：hx-target 是弹层内部的错误槽。 */
    private static MockHttpServletRequestBuilder modalHtmx(MockHttpServletRequestBuilder b) {
        return b.header("HX-Request", "true").header("HX-Target", "confirm-modal-err");
    }

    @Test
    @DisplayName("新建直接生效（§0-1）→ 游客候选可见 → H8 停用（服务端弹层）→ 不可见 → H9 启用 → 可见；审计齐全")
    void createActivateDeactivateRoundTrip() throws Exception {
        Authentication ed = editor();
        String name = "opsp-flow-" + SEQ.incrementAndGet();

        // 新建抽屉（H3 / H4）：三区、EN 在左两列、两个上传位限 1MB、跳转类型带占位且无默认、频次 / 人群单选；无门槛属性、无 token / 状态徽标
        String drawer = mvc.perform(get(BASE).param("create", "1").param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).doesNotContain("<html").contains("f-opsp-name").contains("hx-post=\"" + BASE + "\"")
                .contains("① 内容").contains("② 跳转").contains("③ 投放规则").contains("data-drawer-title=\"新建运营弹窗\"")
                .containsPattern("<option value=\"\" selected=\"selected\">请选择跳转类型")
                .containsPattern("type=\"radio\" name=\"frequency\" value=\"DAILY\" checked")
                .containsPattern("type=\"radio\" name=\"audience\" value=\"ALL\" checked")
                .contains("data-opsp-preview").contains("返回编辑")
                .doesNotContain("data-requires").doesNotContain("opsp-meta").doesNotContain("<fieldset");
        assertThat(drawer.split("data-max-bytes=\"1048576\"", -1)).hasSize(3);
        assertThat(drawer.indexOf("name=\"buttonTextEn\"")).as("EN 在左").isLessThan(drawer.indexOf("name=\"buttonTextId\""));
        assertThat(drawer.indexOf("name=\"imageUrlEn\"")).isLessThan(drawer.indexOf("name=\"imageUrlId\""));

        MvcResult created = mvc.perform(htmx(form(post(BASE), name)).with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        String body = created.getResponse().getContentAsString();
        assertThat(body).doesNotContain("<html").contains("hx-swap-oob").contains("id=\"ops-popup-rows\"").contains(name);
        assertThat(created.getResponse().getHeader("HX-Trigger")).contains("admin:drawer-close");

        Map<String, Object> row = rowByName(name);
        String token = (String) row.get("public_token");
        assertThat(row.get("status")).as("新建直接 ACTIVE").isEqualTo("ACTIVE");
        assertThat(auditCount("OPS_POPUP_CREATED", token)).isEqualTo(1);
        List<JsonNode> live = candidates();
        assertThat(live).hasSize(1);
        assertThat(live.get(0).get("token").asText()).isEqualTo(token);
        assertThat(live.get(0).get("target").get("value").asText()).isEqualTo("KNOW_YOUR_PET");

        // H8：行内「停用」先拉服务端确认弹层（HX-Retarget 到 #confirm-host）
        MvcResult modal = mvc.perform(rowHtmx(get(BASE + "/" + token + "/confirm/deactivate"))
                        .param("lang", "zh_CN").param("status", "RUNNING").with(authentication(ed)))
                .andExpect(status().isOk()).andReturn();
        assertThat(modal.getResponse().getHeader("HX-Retarget")).isEqualTo("#confirm-host");
        assertThat(modal.getResponse().getContentAsString()).contains("停用「" + name + "」？")
                .contains("停用后立即不再弹出。可在列表中重新启用。操作将记入审计。")
                .contains("hx-post=\"" + BASE + "/" + token + "/deactivate\"").contains("btn btn-danger")
                .containsPattern("name=\"status\" value=\"RUNNING\"");

        MvcResult off = mvc.perform(modalHtmx(post(BASE + "/" + token + "/deactivate")).param("status", "RUNNING")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(off.getResponse().getContentAsString()).contains("hx-swap-oob").contains("id=\"ops-popup-rows\"")
                .as("按原筛选（进行中）重拉：刚停用的这条不在结果里").doesNotContain("ops-popup-row-" + token);
        assertThat(off.getResponse().getHeader("HX-Trigger-After-Swap")).isEqualTo("admin:confirm-close");
        assertThat(off.getResponse().getHeader("HX-Trigger")).as("不关抽屉").isNull();
        assertThat(candidates()).isEmpty();
        assertThat(auditCount("OPS_POPUP_DEACTIVATED", token)).isEqualTo(1);

        // H9：启用弹层（品牌色确认钮）→ 启用
        String on = mvc.perform(rowHtmx(get(BASE + "/" + token + "/confirm/activate")).param("lang", "zh_CN")
                        .with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(on).contains("启用「" + name + "」？").contains("已看过的用户不会再弹").contains("btn btn-primary");
        mvc.perform(modalHtmx(post(BASE + "/" + token + "/activate")).with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk());
        assertThat(candidates()).hasSize(1);
        assertThat(auditCount("OPS_POPUP_ACTIVATED", token)).isEqualTo(1);

        // 列表（H1）：合一状态「进行中」+ 行内链接按状态（进行中：编辑 / 停用，无启用 / 删除）；URL 里只有 token
        String list = mvc.perform(get(BASE).param("lang", "zh_CN").with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("ops-popup-row-" + token).contains("进行中").contains("id=\"confirm-host\"")
                .contains(BASE + "/" + token + "/confirm/deactivate")
                .doesNotContain(BASE + "/" + token + "/confirm/activate")
                .doesNotContain(BASE + "/" + token + "/confirm/delete")
                .doesNotContain(BASE + "/" + row.get("id") + "/");
    }

    @Test
    @DisplayName("列表（H1 / H2）：状态筛选 + 名称搜索 + 四格汇总；时间 MM/dd HH:mm – MM/dd HH:mm；人群「新用户（N 天内）」；空库出空态")
    void listFiltersSearchAndSummary() throws Exception {
        Authentication ed = editor();
        String empty = mvc.perform(get(BASE).param("lang", "zh_CN").with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(empty).contains("还没有任何运营弹窗。").doesNotContain("opsp-sum-item")
                .contains("状态：全部").contains("搜索名称");

        String tag = "opsp-f-" + SEQ.incrementAndGet();
        mvc.perform(form(post(BASE), tag + "-live", "startsAt", "2020-10-10T00:00", "endsAt", "2099-10-13T23:59")
                .with(authentication(ed)).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(form(post(BASE), tag + "-soon", "startsAt", "2099-10-15T00:00", "endsAt", "2099-10-31T23:59",
                "audience", "NEW", "newUserDays", "7").with(authentication(ed)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(form(post(BASE), tag + "-old", "startsAt", "2020-09-20T00:00", "endsAt", "2020-09-30T23:59",
                "audience", "OLD", "newUserDays", "30").with(authentication(ed)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        String all = mvc.perform(get(BASE).param("lang", "zh_CN").with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(all).contains("opsp-sum-item").contains("10/15 00:00 – 10/31 23:59")
                .contains("新用户（7 天内）").contains("老用户（30 天以上）").contains("未开始").contains("已结束")
                .as("未开始的那条有删除").contains("/confirm/delete");

        String pending = mvc.perform(get(BASE).param("status", "PENDING").header("HX-Request", "true")
                        .param("lang", "zh_CN").with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(pending).doesNotContain("<html").contains(tag + "-soon").doesNotContain(tag + "-live")
                .doesNotContain(tag + "-old");

        String search = mvc.perform(get(BASE).param("q", (tag + "-LIVE").toUpperCase()).header("HX-Request", "true")
                        .with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(search).contains(tag + "-live").doesNotContain(tag + "-soon");

        String none = mvc.perform(get(BASE).param("q", "no-such-name-xyz").header("HX-Request", "true")
                        .param("lang", "zh_CN").with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(none).contains("没有符合条件的弹窗。").contains("opsp-sum-item");
    }

    @Test
    @DisplayName("删除（H10 · §0-2）：未开始可删 + 审计 DELETED；有曝光 / 已停用未开始 / 已开始 → 409，弹层也拉不出")
    void deleteOnlyNotStarted() throws Exception {
        Authentication ed = editor();
        String tag = "opsp-del-" + SEQ.incrementAndGet();
        for (String suffix : List.of("-a", "-seen", "-off")) {
            mvc.perform(form(post(BASE), tag + suffix, "startsAt", "2099-01-01T00:00", "endsAt", "2099-02-01T00:00")
                    .with(authentication(ed)).with(csrf())).andExpect(status().is3xxRedirection());
        }
        mvc.perform(form(post(BASE), tag + "-live").with(authentication(ed)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        String a = (String) rowByName(tag + "-a").get("public_token");
        String seen = (String) rowByName(tag + "-seen").get("public_token");
        String offT = (String) rowByName(tag + "-off").get("public_token");
        String live = (String) rowByName(tag + "-live").get("public_token");
        // 上线后又把开始时间后移：有曝光记录 → 不能删
        long uid = newUser().getId();
        jdbc.update("INSERT INTO ops_popup_impressions (popup_id, user_id, shown_at) VALUES (?, ?, now())",
                rowByName(tag + "-seen").get("id"), uid);
        jdbc.update("UPDATE ops_popups SET status = 'DISABLED' WHERE public_token = ?", offT);

        String modal = mvc.perform(rowHtmx(get(BASE + "/" + a + "/confirm/delete")).param("lang", "zh_CN")
                        .with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(modal).contains("删除「" + tag + "-a」？").contains("该弹窗还未开始，删除后不可恢复。")
                .contains("btn btn-danger");
        MvcResult del = mvc.perform(modalHtmx(post(BASE + "/" + a + "/delete")).with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(del.getResponse().getHeader("HX-Trigger-After-Swap")).isEqualTo("admin:confirm-close");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_popups WHERE public_token = ?", Integer.class, a)).isZero();
        assertThat(auditCount("OPS_POPUP_DELETED", a)).isEqualTo(1);

        for (String t : List.of(seen, offT, live)) {
            MvcResult r = mvc.perform(modalHtmx(post(BASE + "/" + t + "/delete")).param("lang", "zh_CN")
                            .with(authentication(ed)).with(csrf()))
                    .andExpect(status().isConflict()).andReturn();
            assertThat(r.getResponse().getHeader("HX-Retarget")).isEqualTo("#confirm-modal-err");
            assertThat(r.getResponse().getContentAsString()).contains("data-code=\"admin.err.opsPopup.deleteNotPending\"");
            mvc.perform(rowHtmx(get(BASE + "/" + t + "/confirm/delete")).with(authentication(ed)))
                    .andExpect(status().isConflict());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_popups WHERE public_token = ?", Integer.class, t))
                    .isEqualTo(1);
            assertThat(auditCount("OPS_POPUP_DELETED", t)).isZero();
        }
        // 列表上「删除」链接只给可删的：有曝光的未开始那条不出现；已停用的只有「启用」
        String list = mvc.perform(get(BASE).with(authentication(ed))).andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(BASE + "/" + seen + "/confirm/delete")
                .doesNotContain(BASE + "/" + offT + "/confirm/delete")
                .contains(BASE + "/" + offT + "/confirm/activate");
    }

    @Test
    @DisplayName("开始时间锁：已开始的弹窗把开始时间改到未来 → 422 startLocked（挂 startsAt）、库不变、仍不可删；未开始的可改、可删")
    void startedPopupCannotMoveStartToFuture() throws Exception {
        Authentication ed = editor();
        String tag = "opsp-lock-" + SEQ.incrementAndGet();
        mvc.perform(form(post(BASE), tag + "-live").with(authentication(ed)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(form(post(BASE), tag + "-pend", "startsAt", "2099-01-01T00:00", "endsAt", "2099-02-01T00:00")
                .with(authentication(ed)).with(csrf())).andExpect(status().is3xxRedirection());
        String live = (String) rowByName(tag + "-live").get("public_token");
        String pend = (String) rowByName(tag + "-pend").get("public_token");

        // 游客看过但没有曝光记录（游客不落库）：只靠开始时间锁挡住「改回未开始再删」
        MvcResult locked = mvc.perform(htmx(form(post(BASE + "/" + live), tag + "-live", "startsAt", "2099-01-01T00:00"))
                        .param("lang", "zh_CN").with(authentication(ed)).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(locked.getResponse().getContentAsString()).contains("已开始过的弹窗不能把开始时间改到未来")
                .contains("data-invalid=\"startsAt\"");
        assertThat(jdbc.queryForObject("SELECT starts_at < now() FROM ops_popups WHERE public_token = ?",
                Boolean.class, live)).isTrue();
        assertThat(auditCount("OPS_POPUP_UPDATED", live)).isZero();
        mvc.perform(modalHtmx(post(BASE + "/" + live + "/delete")).with(authentication(ed)).with(csrf()))
                .andExpect(status().isConflict());

        // 已开始的改别的字段（开始时间不动）照常保存
        mvc.perform(htmx(form(post(BASE + "/" + live), tag + "-live", "priority", "8"))
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk());
        assertThat(rowByName(tag + "-live").get("priority")).isEqualTo(8);

        // 未开始：开始时间改到另一个未来时刻 → 保存成功，仍可删
        mvc.perform(htmx(form(post(BASE + "/" + pend), tag + "-pend", "startsAt", "2098-06-01T00:00",
                        "endsAt", "2099-02-01T00:00")).with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk());
        assertThat(auditCount("OPS_POPUP_UPDATED", pend)).isEqualTo(1);
        mvc.perform(modalHtmx(post(BASE + "/" + pend + "/delete")).with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_popups WHERE public_token = ?", Integer.class, pend))
                .isZero();
    }

    @Test
    @DisplayName("编辑：原记录更新，id 与 token 不变，状态不被编辑改掉；审计 UPDATED；抽屉标题「编辑运营弹窗」")
    void editKeepsIdAndToken() throws Exception {
        Authentication ed = editor();
        String name = "opsp-edit-" + SEQ.incrementAndGet();
        mvc.perform(form(post(BASE), name).with(authentication(ed)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        Map<String, Object> before = rowByName(name);
        String token = (String) before.get("public_token");
        mvc.perform(post(BASE + "/" + token + "/deactivate").with(authentication(ed)).with(csrf()));

        String drawer = mvc.perform(get(BASE).param("open", token).param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).doesNotContain("<html").contains(BASE + "/" + token).contains("value=\"" + name + "\"")
                .contains("data-drawer-title=\"编辑运营弹窗\"")
                .containsPattern("value=\"KNOW_YOUR_PET\"[^>]*selected").contains(IMG);

        mvc.perform(htmx(form(post(BASE + "/" + token), name, "priority", "9", "titleEn", "Hello"))
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk());
        Map<String, Object> after = rowByName(name);
        assertThat(after.get("id")).isEqualTo(before.get("id"));
        assertThat(after.get("public_token")).isEqualTo(token);
        assertThat(after.get("priority")).isEqualTo(9);
        assertThat(after.get("title_en")).isEqualTo("Hello");
        assertThat(after.get("status")).as("编辑不改启停").isEqualTo("DISABLED");
        assertThat(auditCount("OPS_POPUP_UPDATED", token)).isEqualTo(1);
    }

    @Test
    @DisplayName("H7：抽屉保存校验失败 → 一次 422（汇总条 + 各槽 oob 红字 + 标红名单）；不换表、不落库")
    void validationErrorsAreCollectedInOne422() throws Exception {
        String name = "opsp-422-" + SEQ.incrementAndGet();
        MvcResult res = mvc.perform(htmx(form(post(BASE), name, "buttonTextEn", "", "targetType", "",
                        "targetValue", "", "endsAt", "2019-12-31T00:00")).param("lang", "zh_CN")
                        .with(authentication(editor())).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        String body = res.getResponse().getContentAsString();
        assertThat(body).contains("还有 3 项需要修改，请检查标红的字段。")
                .contains("请填写 EN 与 ID 两种按钮文案").contains("请选择跳转类型").contains("结束时间须晚于开始时间")
                .containsPattern("id=\"opsp-err-button\" hx-swap-oob=\"innerHTML\"")
                .containsPattern("id=\"opsp-err-name\" hx-swap-oob=\"innerHTML\"></div>")
                .contains("data-invalid=\"buttonTextEn,targetType,startsAt,endsAt\"")
                .doesNotContain("id=\"ops-popup-rows\"");

        MvcResult ext = mvc.perform(htmx(form(post(BASE), name, "targetType", "EXTERNAL", "targetValue", "ftp://x.example/a"))
                        .with(authentication(editor())).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(ext.getResponse().getContentAsString()).contains("data-codes=\"admin.err.opsPopup.targetExternal\"");
        for (String[] bad : new String[][] {{"name", ""}, {"priority", ""}, {"endsAt", ""}, {"buttonTextId", ""}}) {
            mvc.perform(htmx(form(post(BASE), name, bad[0], bad[1])).with(authentication(editor())).with(csrf()))
                    .andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(htmx(form(post(BASE), name, "imageUrlId", "", "imageUrlEn", "")).with(authentication(editor())).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(htmx(form(post(BASE), name, "targetType", "PRODUCT", "targetValue", "nosuchprod"))
                        .with(authentication(editor())).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_popups WHERE name = ?", Integer.class, name))
                .isZero();
    }

    @Test
    @DisplayName("只传 EN 图：存进 image_url_id、image_url_en 留空；App 两种语言都拿到这张图")
    void enOnlyImageIsSharedByBothLanguages() throws Exception {
        String name = "opsp-en-" + SEQ.incrementAndGet();
        mvc.perform(htmx(form(post(BASE), name, "imageUrlId", "", "imageUrlEn", "https://cdn.example/popup/en.jpg"))
                        .with(authentication(editor())).with(csrf()))
                .andExpect(status().isOk());
        Map<String, Object> row = jdbc.queryForMap("SELECT image_url_id, image_url_en FROM ops_popups WHERE name = ?", name);
        assertThat(row.get("image_url_id")).isEqualTo("https://cdn.example/popup/en.jpg");
        assertThat(row.get("image_url_en")).isNull();
        assertThat(candidates()).hasSize(1);
    }

    @Test
    @DisplayName("🔒 无 ops_popup.manage（即便持 config.view + config.edit）：页面、抽屉与全部 POST 一律 403")
    void accountWithoutOpsPopupManageIsLockedOut() throws Exception {
        Authentication ed = editor();
        String name = "opsp-ro-" + SEQ.incrementAndGet();
        mvc.perform(form(post(BASE), name).with(authentication(ed)).with(csrf()));
        String token = (String) rowByName(name).get("public_token");

        // V1.3.4 返工清单 §1：运营弹窗改单一码 ops_popup.manage，不再沿用 config.*（无码 = 侧栏不出现、进不来）
        Authentication configOnly = staffWith(AdminPermissions.CONFIG_VIEW, AdminPermissions.CONFIG_EDIT);
        mvc.perform(get(BASE).with(authentication(configOnly))).andExpect(status().isForbidden());
        mvc.perform(get(BASE).param("open", token).header("HX-Request", "true").with(authentication(configOnly)))
                .andExpect(status().isForbidden());
        mvc.perform(form(post(BASE), name + "x").with(authentication(configOnly)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(htmx(post(BASE + "/" + token + "/activate")).with(authentication(configOnly)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(rowHtmx(post(BASE + "/" + token + "/deactivate")).with(authentication(configOnly)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(modalHtmx(post(BASE + "/" + token + "/delete")).with(authentication(configOnly)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(rowHtmx(get(BASE + "/" + token + "/confirm/delete")).with(authentication(configOnly)))
                .andExpect(status().isForbidden());
        mvc.perform(form(post(BASE + "/" + token), name, "priority", "99").with(authentication(configOnly)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(htmx(form(post(BASE + "/" + token), name, "priority", "99")).with(authentication(configOnly)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(rowByName(name).get("priority")).as("编辑被拒，优先级未变").isEqualTo(3);
        mvc.perform(multipart(BASE + "/images").file(new MockMultipartFile("file", "a.png", "image/png", png(10, 10)))
                        .with(authentication(configOnly)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(rowByName(name).get("status")).isEqualTo("ACTIVE");
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
    @DisplayName("行内拉确认弹层失败（token 不存在）→ 404 落进列表上方的行内错误槽，不弹层、不关抽屉")
    void rowConfirmErrorLandsInVisibleSlot() throws Exception {
        MvcResult res = mvc.perform(rowHtmx(get(BASE + "/nosuchtoken/confirm/deactivate"))
                        .param("lang", "zh_CN").with(authentication(editor())))
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
            OpsPopup p = popupRepo.findByPublicToken(token).orElseThrow(); // 装载时 ACTIVE（新建直接生效）
            // 模拟另一人此刻点了「停用」（直接改库，实体快照仍是 ACTIVE）
            jdbc.update("UPDATE ops_popups SET status = 'DISABLED' WHERE public_token = ?", token);
            p.update(name + "-v2", p.getStartsAt(), p.getEndsAt(), p.getPriority(), p.getAudience(),
                    p.getNewUserDays(), p.getFrequency(), p.getImageUrlId(), p.getImageUrlEn(), p.getTitleId(),
                    p.getTitleEn(), p.getBodyId(), p.getBodyEn(), p.getButtonTextId(), p.getButtonTextEn(),
                    p.getTargetType(), p.getTargetValue());
        });
        Map<String, Object> after = rowByName(name + "-v2");
        assertThat(after.get("status")).as("编辑只写脏列，不把 ACTIVE 写回").isEqualTo("DISABLED");
    }

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
