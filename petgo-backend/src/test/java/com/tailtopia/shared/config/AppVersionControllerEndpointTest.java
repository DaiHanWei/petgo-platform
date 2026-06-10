package com.tailtopia.shared.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * {@link AppVersionController} 端点集成测试（Story 6.5）：{@code GET /api/v1/app-version}。
 *
 * <p>断言：游客（无 token）放行 200，且四字段（latestVersion / minSupportedVersion /
 * iosStoreUrl / androidStoreUrl）回显 {@code petgo.app-version} 配置默认值——dev profile 下
 * env 未注入，取 {@link AppVersionProperties} 内置默认：latest/minSupported=1.0.0、两个 store url 空串。
 */
class AppVersionControllerEndpointTest extends ApiIntegrationTest {

    /** 游客可读：缺 token → 200（permitAll 锚点），并回显配置默认字段值。 */
    @Test
    void appVersion_isOpenToGuest_andReturnsConfiguredDefaults() throws Exception {
        mvc.perform(get("/api/v1/app-version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion").value("1.0.0"))
                .andExpect(jsonPath("$.minSupportedVersion").value("1.0.0"))
                .andExpect(jsonPath("$.iosStoreUrl").value(""))
                .andExpect(jsonPath("$.androidStoreUrl").value(""));
    }

    /** 带合法 token 同样可读（放行端点不因登录态而改变响应）。 */
    @Test
    void appVersion_withToken_sameFields() throws Exception {
        User u = newUser();
        mvc.perform(get("/api/v1/app-version").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion").value("1.0.0"))
                .andExpect(jsonPath("$.minSupportedVersion").value("1.0.0"));
    }
}
