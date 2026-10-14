package com.tailtopia.shared.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.config.service.AppVersionConfigService;
import com.tailtopia.config.service.AppVersionConfigUpdate;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * {@link AppVersionController} 端点集成测试（Story 6.5；V1.3.4 Story 3.1 改字段）：{@code GET /api/v1/app-version}。
 *
 * <p>断言：游客（无 token）放行 200，且 5 个字段回显 {@code app_version_config} 迁移预置值
 * （V20261012_1104：两端最新 1.3.3、最低 1.0.0、两条标准商店链接）；旧字段 {@code latestVersion} 不再返回。
 * 改配置后下一次请求立即返回新值（不缓存）。
 */
class AppVersionControllerEndpointTest extends ApiIntegrationTest {

    private static final String IOS_URL = "https://apps.apple.com/app/id6785361196";
    private static final String ANDROID_URL = "https://play.google.com/store/apps/details?id=com.tailtopia.app";

    @Autowired
    private AppVersionConfigService appVersionConfigService;

    /** 测试库共享：每个用例后写回迁移预置值，避免污染其它用例。 */
    @AfterEach
    void restoreSeed() {
        appVersionConfigService.update(
                new AppVersionConfigUpdate("1.3.3", "1.3.3", "1.0.0", IOS_URL, ANDROID_URL),
                adminActorId());
    }

    /** 游客可读：缺 token → 200（permitAll 锚点），并回显迁移预置值。 */
    @Test
    void appVersion_isOpenToGuest_andReturnsConfiguredDefaults() throws Exception {
        mvc.perform(get("/api/v1/app-version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iosLatestVersion").value("1.3.3"))
                .andExpect(jsonPath("$.androidLatestVersion").value("1.3.3"))
                .andExpect(jsonPath("$.minSupportedVersion").value("1.0.0"))
                .andExpect(jsonPath("$.iosStoreUrl").value(IOS_URL))
                .andExpect(jsonPath("$.androidStoreUrl").value(ANDROID_URL))
                .andExpect(jsonPath("$.latestVersion").doesNotExist())
                .andExpect(jsonPath("$.length()").value(5));
    }

    /** 带合法 token 同样可读（放行端点不因登录态而改变响应）。 */
    @Test
    void appVersion_withToken_sameFields() throws Exception {
        User u = newUser();
        mvc.perform(get("/api/v1/app-version").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iosLatestVersion").value("1.3.3"))
                .andExpect(jsonPath("$.androidLatestVersion").value("1.3.3"))
                .andExpect(jsonPath("$.minSupportedVersion").value("1.0.0"))
                .andExpect(jsonPath("$.iosStoreUrl").value(IOS_URL))
                .andExpect(jsonPath("$.androidStoreUrl").value(ANDROID_URL))
                .andExpect(jsonPath("$.latestVersion").doesNotExist());
    }

    /** 改 iOS 最新版本后下一次请求立即生效（不缓存），Android 不受影响。 */
    @Test
    void appVersion_reflectsUpdateImmediately() throws Exception {
        appVersionConfigService.update(
                new AppVersionConfigUpdate("1.3.9", "1.3.3", "1.0.0", IOS_URL, ANDROID_URL),
                adminActorId());

        mvc.perform(get("/api/v1/app-version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iosLatestVersion").value("1.3.9"))
                .andExpect(jsonPath("$.androidLatestVersion").value("1.3.3"));
    }
}
