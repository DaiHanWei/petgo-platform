package com.tailtopia.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * L1（V1.3.4 AB-22 · FR-124；2026-10-15 按后台 PRD §1 / UI 稿 G1–G8 返工，admin-doc-rework-v1.3.4.md §2）：
 * 后台「App 版本更新」卡 —— 预检端点回 G3 / G4 服务端确认弹层、最低支持版本单独权限码、只读行、分端报错。
 * 原 spec I/O 矩阵（以下）仍逐行覆盖：
 * 改最新版本 → htmx 成功回卡 + toast、App 接口立即返回新值、每个变更字段一条 APP_VERSION 日志 + 一条
 * {@code CONFIG_UPDATE_APP_VERSION} 审计；无变化不写；四个校验码 + 确认值不符 422 且不写库；只读账号卡片只读、POST 403；
 * 512 字符商店链接整条记进变更日志（V20261014_2304 加宽）。
 *
 * <p>⚠️ {@code app_version_config} 是共享测试库里的单行配置：每个用例前快照、后还原，并先置成已知基线。
 */
class AdminAppVersionConfigIntegrationTest extends ApiIntegrationTest {

    private static final String IOS_URL = "https://apps.apple.com/app/id6785361196";
    private static final String ANDROID_URL = "https://play.google.com/store/apps/details?id=com.tailtopia.app";

    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;

    private Map<String, Object> saved;

    @BeforeEach
    void snapshotAndBaseline() {
        saved = jdbc.queryForMap("SELECT ios_latest_version, android_latest_version, min_supported_version, ios_store_url,"
                + " android_store_url FROM app_version_config WHERE id = 1");
        write("1.3.3", "1.3.3", "1.0.0", IOS_URL, ANDROID_URL);
    }

    @AfterEach
    void restore() {
        write((String) saved.get("ios_latest_version"), (String) saved.get("android_latest_version"),
                (String) saved.get("min_supported_version"), (String) saved.get("ios_store_url"),
                (String) saved.get("android_store_url"));
    }

    private void write(String ios, String android, String min, String iosUrl, String androidUrl) {
        jdbc.update("UPDATE app_version_config SET ios_latest_version = ?, android_latest_version = ?, min_supported_version = ?,"
                + " ios_store_url = ?, android_store_url = ? WHERE id = 1", ios, android, min, iosUrl, androidUrl);
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM app_version_config WHERE id = 1");
    }

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("appver-" + n + "@tailtopia.test", "版本配置超管", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("appver-op-" + n + "@tailtopia.test", "版本配置运营", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), AdminAccountType.STAFF,
                java.util.Set.of(codes));
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    /** 审计仓储不继承 CrudRepository（哈希链只追加），计数走 SQL。 */
    private long auditCount(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE action_type = ?", Long.class, action);
    }

    private long changeLogCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM config_change_logs WHERE config_type = 'APP_VERSION'", Long.class);
    }

    private static MockHttpServletRequestBuilder save(Authentication who, String ios, String android, String min,
            String iosUrl, String androidUrl, String minConfirm) {
        MockHttpServletRequestBuilder b = post("/admin/config/app-version").with(authentication(who)).with(csrf())
                .param("iosLatestVersion", ios).param("androidLatestVersion", android).param("minSupportedVersion", min)
                .param("iosStoreUrl", iosUrl).param("androidStoreUrl", androidUrl).param("lang", "zh_CN");
        return minConfirm == null ? b : b.param("minConfirm", minConfirm);
    }

    private static MockHttpServletRequestBuilder htmx(MockHttpServletRequestBuilder b) {
        return b.header("HX-Request", "true").header("HX-Target", "cfg-app-version-err");
    }

    /** 弹层里的提交：hx-target 是弹层内部错误槽（confirm-modal.html）。 */
    private static MockHttpServletRequestBuilder fromModal(MockHttpServletRequestBuilder b) {
        return b.header("HX-Request", "true").header("HX-Target", "confirm-modal-err");
    }

    /** 卡上「保存版本配置」→ 只校验不写库的预检端点（回 G3 / G4 弹层）。 */
    private static MockHttpServletRequestBuilder confirm(Authentication who, String ios, String android, String min,
            String iosUrl, String androidUrl) {
        return post("/admin/config/app-version/confirm").with(authentication(who)).with(csrf())
                .param("iosLatestVersion", ios).param("androidLatestVersion", android).param("minSupportedVersion", min)
                .param("iosStoreUrl", iosUrl).param("androidStoreUrl", androidUrl).param("lang", "zh_CN")
                .header("HX-Request", "true").header("HX-Target", "cfg-app-version-err");
    }

    /** UI 稿 G1：卡片排在「一次性解锁定价」之后、PawCoin 之前；行式布局 + 最低支持版本高危行；保存走预检端点，不挂浏览器 confirm。 */
    @Test
    void pageShowsCardWithCurrentFiveValuesAfterKtpCard() throws Exception {
        String html = mvc.perform(get("/admin/config").param("lang", "zh_CN").with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"cfg-app-version\"").contains("action=\"/admin/config/app-version\"")
                .contains("hx-post=\"/admin/config/app-version/confirm\"").contains("id=\"cfg-app-version-err\"")
                .doesNotContainPattern("id=\"cfg-app-version\"[^>]*data-confirm-diff")
                .containsPattern("id=\"cfg-app-version\"[^>]*data-error-fields=")
                .contains(">App 版本更新<").contains("保存版本配置").contains("仅在 Google Play 上架新版后修改。")
                .contains("强制更新、无法跳过").containsPattern("class=\"prow hot\"[\\s\\S]*?<span class=\"risk\">高危</span>")
                .contains("value=\"1.3.3\"").contains("value=\"1.0.0\"")
                .contains("value=\"" + IOS_URL + "\"").contains("value=\"" + ANDROID_URL + "\"")
                // 前端即时校验的文案（分端报错带 {0} 占位）挂在卡上
                .contains("data-msg-min-gt-ios=\"最低支持版本不能高于 iOS 最新版本号（{0}）\"")
                .contains("data-msg-min-gt-android=\"最低支持版本不能高于 Android 最新版本号（{0}）\"")
                // 旧的卡内「再次输入」确认框已删除；超管该行可编辑、无只读注
                .doesNotContain("name=\"minConfirm\"").doesNotContain("data-av-min-readonly")
                .doesNotContainPattern("name=\"minSupportedVersion\"[^>]*readonly")
                .contains("id=\"confirm-host\"");
        assertThat(html.indexOf("id=\"cfg-ktp\"")).isLessThan(html.indexOf("id=\"cfg-app-version\""));
        assertThat(html.indexOf("id=\"cfg-app-version\"")).isLessThan(html.indexOf("id=\"cfg-pawcoin\""));
    }

    /** 矩阵·改最新版本：htmx 成功回卡 + toast；App 接口立即返回新值；两条日志 + 一条审计；不需确认值。 */
    @Test
    void changingLatestVersionsSavesLogsAuditsAndTakesEffectOnTheApi() throws Exception {
        long logsBefore = changeLogCount();
        long auditsBefore = auditCount("CONFIG_UPDATE_APP_VERSION");

        String ok = mvc.perform(fromModal(save(superAdmin(), "1.3.4", "1.3.4", "1.0.0", IOS_URL, ANDROID_URL, null)))
                .andExpect(status().isOk()).andExpect(header().string("HX-Retarget", "#cfg-app-version"))
                .andExpect(header().string("HX-Reswap", "outerHTML"))
                .andExpect(header().string("HX-Trigger-After-Swap", "admin:confirm-close"))
                .andReturn().getResponse().getContentAsString();
        assertThat(ok).contains("id=\"cfg-app-version\"").contains("value=\"1.3.4\"")
                .contains("hx-swap-oob=\"beforeend:#admin-toast-host\"").contains("App 版本配置已更新（操作留审计）").doesNotContain("id=\"cfg-pricing\"");

        mvc.perform(get("/api/v1/app-version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iosLatestVersion").value("1.3.4"))
                .andExpect(jsonPath("$.androidLatestVersion").value("1.3.4"))
                .andExpect(jsonPath("$.minSupportedVersion").value("1.0.0"));
        assertThat(changeLogCount()).isEqualTo(logsBefore + 2);
        assertThat(auditCount("CONFIG_UPDATE_APP_VERSION")).isEqualTo(auditsBefore + 1);
        List<String> fields = jdbc.queryForList("SELECT field FROM config_change_logs WHERE config_type = 'APP_VERSION'"
                + " ORDER BY id DESC LIMIT 2", String.class);
        assertThat(fields).containsExactlyInAnyOrder("ios_latest_version", "android_latest_version");
    }

    /** 矩阵·未改任何值：成功提示（PRG toast），不写库 / 日志 / 审计。 */
    @Test
    void unchangedSubmitWritesNothing() throws Exception {
        Object updatedAt = row().get("updated_at");
        long logsBefore = changeLogCount();
        long auditsBefore = auditCount("CONFIG_UPDATE_APP_VERSION");
        mvc.perform(save(superAdmin(), " 1.3.3 ", "1.3.3", "1.0.0", IOS_URL, ANDROID_URL, null))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/config"))
                .andExpect(flash().attributeExists("toast"));
        assertThat(row().get("updated_at")).isEqualTo(updatedAt);
        assertThat(changeLogCount()).isEqualTo(logsBefore);
        assertThat(auditCount("CONFIG_UPDATE_APP_VERSION")).isEqualTo(auditsBefore);
    }

    /** 矩阵·校验码（min-gt 分 iOS / Android）+ 确认值不符：htmx 422 行内 err（data-code）且一律不写库。 */
    @Test
    void invalidInputsAre422InlineAndWriteNothing() throws Exception {
        long logsBefore = changeLogCount();
        String[][] cases = {
                // ios, android, min, iosUrl, androidUrl, minConfirm, 期望码
                {"1.3", "1.3.3", "1.0.0", IOS_URL, ANDROID_URL, null, "admin.err.config.version.format"},
                {"01.2.3", "1.3.3", "1.0.0", IOS_URL, ANDROID_URL, null, "admin.err.config.version.format"},
                {"1.3.4", "1.3.4", "1.4.0", IOS_URL, ANDROID_URL, "1.4.0", "admin.err.config.version.min-gt-ios-latest"},
                {"1.5.0", "1.3.4", "1.4.0", IOS_URL, ANDROID_URL, "1.4.0", "admin.err.config.version.min-gt-android-latest"},
                {"1.3.3", "1.3.3", "1.1.0", "", ANDROID_URL, "1.1.0", "admin.err.config.store-url.blank"},
                {"1.3.3", "1.3.3", "1.1.0", IOS_URL, "", "1.1.0", "admin.err.config.store-url.blank"},
                {"1.3.3", "1.3.3", "1.0.0", "http://apps.apple.com/app/id6785361196", ANDROID_URL, null,
                        "admin.err.config.store-url.scheme"},
                {"1.3.3", "1.3.3", "1.1.0", IOS_URL, ANDROID_URL, null, "admin.err.config.version.min-confirm"},
                {"1.3.3", "1.3.3", "1.1.0", IOS_URL, ANDROID_URL, "", "admin.err.config.version.min-confirm"},
                {"1.3.3", "1.3.3", "1.1.0", IOS_URL, ANDROID_URL, "1.1.1", "admin.err.config.version.min-confirm"},
        };
        for (String[] c : cases) {
            String err = mvc.perform(htmx(save(superAdmin(), c[0], c[1], c[2], c[3], c[4], c[5])))
                    .andExpect(status().isUnprocessableEntity()).andExpect(header().string("HX-Retarget", "#cfg-app-version-err"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(err).as(c[6] + " / " + String.join(",", java.util.Arrays.copyOf(c, 6)))
                    .contains("inline-error").contains("data-code=\"" + c[6] + "\"");
        }
        Map<String, Object> r = row();
        assertThat(r.get("ios_latest_version")).isEqualTo("1.3.3");
        assertThat(r.get("min_supported_version")).isEqualTo("1.0.0");
        assertThat(r.get("ios_store_url")).isEqualTo(IOS_URL);
        assertThat(changeLogCount()).isEqualTo(logsBefore);
        // 整页提交路径维持 PRG：错误进 flash，不写库
        mvc.perform(save(superAdmin(), "1.3.3", "1.3.3", "1.1.0", IOS_URL, ANDROID_URL, null))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/config"))
                .andExpect(flash().attributeExists("error"));
        assertThat(row().get("min_supported_version")).isEqualTo("1.0.0");
    }

    /** AC2 / AC3：最低版本 1.0.0 → 1.1.0、两链接已填、确认值相符 → 保存成功，App 收到新值；审计摘要为改动字段数。 */
    @Test
    void raisingMinWithMatchingConfirmSavesAndAuditsFieldCount() throws Exception {
        long auditsBefore = auditCount("CONFIG_UPDATE_APP_VERSION");
        mvc.perform(save(superAdmin(), "1.3.3", "1.3.3", "1.1.0", IOS_URL, ANDROID_URL, " 1.1.0 "))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("toast"));
        mvc.perform(get("/api/v1/app-version")).andExpect(status().isOk())
                .andExpect(jsonPath("$.minSupportedVersion").value("1.1.0"));
        assertThat(auditCount("CONFIG_UPDATE_APP_VERSION")).isEqualTo(auditsBefore + 1);
        String summary = jdbc.queryForObject("SELECT summary FROM admin_audit_logs WHERE action_type = 'CONFIG_UPDATE_APP_VERSION'"
                + " ORDER BY id DESC LIMIT 1", String.class);
        assertThat(summary).isEqualTo("1 field(s) changed");
        Map<String, Object> log = jdbc.queryForMap("SELECT field, old_value, new_value FROM config_change_logs"
                + " WHERE config_type = 'APP_VERSION' ORDER BY id DESC LIMIT 1");
        assertThat(log).containsEntry("field", "min_supported_version").containsEntry("old_value", "1.0.0")
                .containsEntry("new_value", "1.1.0");
    }

    /** 矩阵·商店链接 512 字符：合法长 https 链接保存成功，变更日志整条记录（不截断、不 500）。 */
    @Test
    void storeUrlOf512CharsIsSavedAndLoggedInFull() throws Exception {
        String prefix = "https://play.google.com/store/apps/details?id=com.tailtopia.app&ref=";
        String longUrl = prefix + "x".repeat(512 - prefix.length());
        assertThat(longUrl).hasSize(512);
        mvc.perform(htmx(save(superAdmin(), "1.3.3", "1.3.3", "1.0.0", IOS_URL, longUrl, null)))
                .andExpect(status().isOk()).andExpect(header().string("HX-Retarget", "#cfg-app-version"));
        assertThat(row().get("android_store_url")).isEqualTo(longUrl);
        Map<String, Object> log = jdbc.queryForMap("SELECT field, old_value, new_value FROM config_change_logs"
                + " WHERE config_type = 'APP_VERSION' ORDER BY id DESC LIMIT 1");
        assertThat(log).containsEntry("field", "android_store_url").containsEntry("old_value", ANDROID_URL)
                .containsEntry("new_value", longUrl);
    }

    /** 矩阵·只读账号：仅 config.view → 卡片只读（输入禁用、无确认框、注明所缺权限）；POST 403（htmx 与整页都拒）。 */
    @Test
    void viewOnlyAccountSeesReadonlyCardAndCannotPost() throws Exception {
        String html = mvc.perform(get("/admin/config").param("lang", "zh_CN").with(authentication(staffWith(AdminPermissions.CONFIG_VIEW))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).containsPattern("id=\"cfg-app-version\"[^>]*data-readonly=\"true\"")
                .containsPattern("name=\"iosLatestVersion\"[^>]*disabled").containsPattern("name=\"androidStoreUrl\"[^>]*disabled")
                .doesNotContain("name=\"minConfirm\"").contains("需要「编辑配置」权限")
                .doesNotContain("data-av-min-readonly");
        mvc.perform(htmx(save(staffWith(AdminPermissions.CONFIG_VIEW), "1.3.4", "1.3.4", "1.0.0", IOS_URL, ANDROID_URL, null)))
                .andExpect(status().isForbidden());
        mvc.perform(save(staffWith(AdminPermissions.CONFIG_VIEW), "1.3.4", "1.3.4", "1.0.0", IOS_URL, ANDROID_URL, null))
                .andExpect(status().isForbidden());
        assertThat(row().get("ios_latest_version")).isEqualTo("1.3.3");
    }

    // ── V1.3.4 后台返工清单 §2：预检端点 + G3 / G4 服务端确认弹层 ─────────────────────────────

    /** G3：只改了最新版本号 → 普通确认弹层（逐字段新旧值 + 按字段提醒语），回 #confirm-host；预检不写库、不审计。 */
    @Test
    void confirmEndpointRendersPlainModalForNonMinChanges() throws Exception {
        Object updatedAt = row().get("updated_at");
        long auditsBefore = auditCount("CONFIG_UPDATE_APP_VERSION");
        String modal = mvc.perform(confirm(superAdmin(), "1.3.3", " 1.3.4 ", "1.0.0", IOS_URL, ANDROID_URL))
                .andExpect(status().isOk()).andExpect(header().string("HX-Retarget", "#confirm-host"))
                .andExpect(header().string("HX-Reswap", "innerHTML")).andReturn().getResponse().getContentAsString();
        assertThat(modal).contains("<dialog").contains("data-autoopen=\"true\"").contains("确认修改 App 版本配置？")
                .containsPattern("Android 最新版本号：</span><s>1\\.3\\.3</s>[\\s\\S]*?<b>1\\.3\\.4</b>")
                .contains("请确认 Google Play 已上架 1.3.4。保存后，低于 1.3.4 的 Android 用户会在首页看到更新提醒。操作将记入审计。")
                .contains("hx-post=\"/admin/config/app-version\"").contains("hx-target=\"#confirm-modal-err\"")
                .containsPattern("type=\"hidden\"[^>]*name=\"androidLatestVersion\"[^>]*value=\"1\\.3\\.4\"")
                .contains("class=\"btn btn-primary\"").contains(">确认修改<").contains(">取消<")
                .doesNotContain("data-typed-confirm").doesNotContain("cm-banner").doesNotContain("iOS 最新版本号：");
        assertThat(row().get("updated_at")).isEqualTo(updatedAt);
        assertThat(row().get("android_latest_version")).isEqualTo("1.3.3");
        assertThat(auditCount("CONFIG_UPDATE_APP_VERSION")).isEqualTo(auditsBefore);
    }

    /** G4：改了最低支持版本（同时改别的也只弹它并列出全部改动）→ 红色警示 + 输入新版本号，确认钮初始禁用、红色。 */
    @Test
    void confirmEndpointRendersTypedModalWhenMinChanges() throws Exception {
        String up = mvc.perform(confirm(superAdmin(), "1.3.4", "1.3.3", "1.2.0", IOS_URL, ANDROID_URL))
                .andExpect(status().isOk()).andExpect(header().string("HX-Retarget", "#confirm-host"))
                .andReturn().getResponse().getContentAsString();
        assertThat(up).contains("确认上调最低支持版本？").contains("此操作将使低于该版本的全部用户无法继续使用 App，且提示不可跳过。")
                .containsPattern("最低支持版本：</span><s>1\\.0\\.0</s>[\\s\\S]*?<b>1\\.2\\.0</b>")
                .contains("iOS 最新版本号：")
                .contains("请输入新版本号 <b>1.2.0</b> 以确认")
                .containsPattern("<input[^>]*name=\"minConfirm\"[^>]*data-typed-confirm[^>]*data-expected=\"1\\.2\\.0\"")
                .containsPattern("<button type=\"submit\" class=\"btn btn-danger\"[^>]*data-typed-confirm-submit[^>]*disabled")
                .contains(">确认上调<");
        // 下调同样走 G4，标题 / 按钮按方向（返工清单 §0-8）
        write("1.3.3", "1.3.3", "1.1.0", IOS_URL, ANDROID_URL);
        String down = mvc.perform(confirm(superAdmin(), "1.3.3", "1.3.3", "1.0.0", IOS_URL, ANDROID_URL))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(down).contains("下调后，版本号介于新旧值之间的用户将不再被强制更新").doesNotContain("无法继续使用 App")
                .contains("确认修改最低支持版本？").contains("data-expected=\"1.0.0\"").contains(">确认修改<")
                .doesNotContain("确认上调");
        assertThat(row().get("min_supported_version")).isEqualTo("1.1.0");
    }

    /** 预检校验不过 → 422 回卡底 err 槽（与保存端点同码，分端报错带版本号），不弹层；无改动 → 422；非 htmx → 回页面。 */
    @Test
    void confirmEndpointRejectsInvalidInputWithSplitCodes() throws Exception {
        String ios = mvc.perform(confirm(superAdmin(), "1.3.3", "1.5.0", "1.4.0", IOS_URL, ANDROID_URL))
                .andExpect(status().isUnprocessableEntity()).andExpect(header().string("HX-Retarget", "#cfg-app-version-err"))
                .andReturn().getResponse().getContentAsString();
        assertThat(ios).contains("data-code=\"admin.err.config.version.min-gt-ios-latest\"")
                .contains("最低支持版本不能高于 iOS 最新版本号（1.3.3）").doesNotContain("<dialog");
        String android = mvc.perform(confirm(superAdmin(), "1.5.0", "1.3.2", "1.4.0", IOS_URL, ANDROID_URL))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(android).contains("data-code=\"admin.err.config.version.min-gt-android-latest\"")
                .contains("最低支持版本不能高于 Android 最新版本号（1.3.2）");
        assertThat(mvc.perform(confirm(superAdmin(), "1.3", "1.3.3", "1.0.0", IOS_URL, ANDROID_URL))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString())
                .contains("data-code=\"admin.err.config.version.format\"").contains("版本号格式应为 x.y.z");
        assertThat(mvc.perform(confirm(superAdmin(), "1.3.3", "1.3.3", "1.1.0", "", ANDROID_URL))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString())
                .contains("data-code=\"admin.err.config.store-url.blank\"");
        assertThat(mvc.perform(confirm(superAdmin(), "1.3.3", "1.3.3", "1.0.0", IOS_URL, ANDROID_URL))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString())
                .contains("data-code=\"admin.err.config.noChange\"");
        mvc.perform(post("/admin/config/app-version/confirm").with(authentication(superAdmin())).with(csrf())
                        .param("iosLatestVersion", "1.3.4").param("androidLatestVersion", "1.3.4").param("minSupportedVersion", "1.0.0"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/config"));
        assertThat(row().get("ios_latest_version")).isEqualTo("1.3.3");
    }

    /** G4 弹层里输错版本号 → 422 回弹层内错误槽，不写库。 */
    @Test
    void modalSubmitWithWrongTypedValueIsRejectedInsideTheModal() throws Exception {
        String err = mvc.perform(fromModal(save(superAdmin(), "1.3.3", "1.3.3", "1.2.0", IOS_URL, ANDROID_URL, "1.2")))
                .andExpect(status().isUnprocessableEntity()).andExpect(header().string("HX-Retarget", "#confirm-modal-err"))
                .andReturn().getResponse().getContentAsString();
        assertThat(err).contains("data-code=\"admin.err.config.version.min-confirm\"");
        assertThat(row().get("min_supported_version")).isEqualTo("1.0.0");
    }

    /**
     * UI 稿 G7：有 config.edit 但没有 config.app_min_version_edit → 只有「最低支持版本」一行只读并注明所缺权限，其余四项可改；
     * 服务端兜底：改最低版本（预检 / 保存）一律 403 min-forbidden、不写库；只改其他字段照常成功。
     */
    @Test
    void editorWithoutMinPermissionGetsReadonlyMinRowAndIsRejectedServerSide() throws Exception {
        Authentication editor = staffWith(AdminPermissions.CONFIG_VIEW, AdminPermissions.CONFIG_EDIT);
        String html = mvc.perform(get("/admin/config").param("lang", "zh_CN").with(authentication(editor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).containsPattern("<input[^>]*name=\"minSupportedVersion\"[^>]*readonly")
                .doesNotContainPattern("<input[^>]*name=\"minSupportedVersion\"[^>]*disabled")
                .contains("只读：需要「修改最低支持版本」权限").contains("data-av-min-readonly")
                .doesNotContainPattern("<input[^>]*name=\"iosLatestVersion\"[^>]*(readonly|disabled)")
                .doesNotContainPattern("<input[^>]*name=\"androidStoreUrl\"[^>]*(readonly|disabled)")
                .doesNotContainPattern("id=\"cfg-app-version\"[^>]*data-readonly");

        String pre = mvc.perform(confirm(editor, "1.3.3", "1.3.3", "1.1.0", IOS_URL, ANDROID_URL))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(pre).contains("data-code=\"admin.err.config.version.min-forbidden\"");
        mvc.perform(fromModal(save(editor, "1.3.3", "1.3.3", "1.1.0", IOS_URL, ANDROID_URL, "1.1.0")))
                .andExpect(status().isForbidden());
        mvc.perform(save(editor, "1.3.3", "1.3.3", "1.1.0", IOS_URL, ANDROID_URL, "1.1.0"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
        assertThat(row().get("min_supported_version")).isEqualTo("1.0.0");

        mvc.perform(confirm(editor, "1.3.4", "1.3.3", "1.0.0", IOS_URL, ANDROID_URL)).andExpect(status().isOk());
        mvc.perform(fromModal(save(editor, "1.3.4", "1.3.3", "1.0.0", IOS_URL, ANDROID_URL, null)))
                .andExpect(status().isOk()).andExpect(header().string("HX-Retarget", "#cfg-app-version"));
        assertThat(row().get("ios_latest_version")).isEqualTo("1.3.4");
    }

    /** 持 config.edit + config.app_min_version_edit（非超管）→ 该行可编辑，按 G4 确认后保存成功。 */
    @Test
    void editorWithMinPermissionCanRaiseMin() throws Exception {
        Authentication editor = staffWith(AdminPermissions.CONFIG_VIEW, AdminPermissions.CONFIG_EDIT,
                AdminPermissions.CONFIG_APP_MIN_VERSION_EDIT);
        String html = mvc.perform(get("/admin/config").param("lang", "zh_CN").with(authentication(editor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContainPattern("<input[^>]*name=\"minSupportedVersion\"[^>]*readonly")
                .doesNotContain("data-av-min-readonly");
        mvc.perform(confirm(editor, "1.3.3", "1.3.3", "1.1.0", IOS_URL, ANDROID_URL))
                .andExpect(status().isOk()).andExpect(header().string("HX-Retarget", "#confirm-host"));
        mvc.perform(fromModal(save(editor, "1.3.3", "1.3.3", "1.1.0", IOS_URL, ANDROID_URL, "1.1.0")))
                .andExpect(status().isOk());
        assertThat(row().get("min_supported_version")).isEqualTo("1.1.0");
    }
}
