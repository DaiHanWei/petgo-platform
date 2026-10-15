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
 * L1（V1.3.4 AB-22 · FR-124）：后台「App 版本配置」卡 —— spec I/O 矩阵逐行覆盖。
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

    /** AC1：超管打开运营配置页 → 卡片在客服卡之后，五个值 = 表内当前值；慎改说明 + 确认框（默认隐藏、禁用）。 */
    @Test
    void pageShowsCardWithCurrentFiveValuesAfterSupportCard() throws Exception {
        String html = mvc.perform(get("/admin/config").param("lang", "zh_CN").with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"cfg-app-version\"").contains("action=\"/admin/config/app-version\"")
                .contains("hx-post=\"/admin/config/app-version\"").contains("id=\"cfg-app-version-err\"")
                .containsPattern("id=\"cfg-app-version\"[^>]*data-confirm-diff=\"true\"")
                .containsPattern("id=\"cfg-app-version\"[^>]*data-error-fields=")
                .contains("App 版本配置").contains("强制更新").contains("value=\"1.3.3\"").contains("value=\"1.0.0\"")
                .contains("value=\"" + IOS_URL + "\"").contains("value=\"" + ANDROID_URL + "\"")
                .containsPattern("data-reveal-when-changed=\"minSupportedVersion\"[^>]*hidden")
                .containsPattern("name=\"minConfirm\"[^>]*disabled");
        assertThat(html.indexOf("id=\"cfg-support\"")).isLessThan(html.indexOf("id=\"cfg-app-version\""));
    }

    /** 矩阵·改最新版本：htmx 成功回卡 + toast；App 接口立即返回新值；两条日志 + 一条审计；不需确认值。 */
    @Test
    void changingLatestVersionsSavesLogsAuditsAndTakesEffectOnTheApi() throws Exception {
        long logsBefore = changeLogCount();
        long auditsBefore = auditCount("CONFIG_UPDATE_APP_VERSION");

        String ok = mvc.perform(htmx(save(superAdmin(), "1.3.4", "1.3.4", "1.0.0", IOS_URL, ANDROID_URL, null)))
                .andExpect(status().isOk()).andExpect(header().string("HX-Retarget", "#cfg-app-version"))
                .andExpect(header().string("HX-Reswap", "outerHTML")).andReturn().getResponse().getContentAsString();
        assertThat(ok).contains("id=\"cfg-app-version\"").contains("value=\"1.3.4\"")
                .contains("hx-swap-oob=\"beforeend:#admin-toast-host\"").contains("App 版本配置已更新").doesNotContain("id=\"cfg-pricing\"");

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

    /** 矩阵·四个校验码 + 确认值不符：htmx 422 卡内行内 err（data-code）且一律不写库。 */
    @Test
    void invalidInputsAre422InlineAndWriteNothing() throws Exception {
        long logsBefore = changeLogCount();
        String[][] cases = {
                // ios, android, min, iosUrl, androidUrl, minConfirm, 期望码
                {"1.3", "1.3.3", "1.0.0", IOS_URL, ANDROID_URL, null, "admin.err.config.version.format"},
                {"01.2.3", "1.3.3", "1.0.0", IOS_URL, ANDROID_URL, null, "admin.err.config.version.format"},
                {"1.3.4", "1.3.4", "1.4.0", IOS_URL, ANDROID_URL, "1.4.0", "admin.err.config.version.min-gt-latest"},
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
                .doesNotContain("name=\"minConfirm\"").contains("需要「编辑配置」权限");
        mvc.perform(htmx(save(staffWith(AdminPermissions.CONFIG_VIEW), "1.3.4", "1.3.4", "1.0.0", IOS_URL, ANDROID_URL, null)))
                .andExpect(status().isForbidden());
        mvc.perform(save(staffWith(AdminPermissions.CONFIG_VIEW), "1.3.4", "1.3.4", "1.0.0", IOS_URL, ANDROID_URL, null))
                .andExpect(status().isForbidden());
        assertThat(row().get("ios_latest_version")).isEqualTo("1.3.3");
    }
}
