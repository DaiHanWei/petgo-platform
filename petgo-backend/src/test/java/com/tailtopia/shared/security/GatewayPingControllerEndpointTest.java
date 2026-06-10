package com.petgo.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgo.auth.domain.User;
import com.petgo.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * {@link GatewayPingController} 端点集成测试（Story 1.5 B3，门控对称性探测，仅 dev profile）。
 *
 * <ul>
 *   <li>{@code GET /api/v1/public/_ping}：游客只读放行 → 200 {@code {ok:true, scope:"public"}}。</li>
 *   <li>{@code POST /api/v1/_guarded-ping}：受保护写——缺 token → 401；带 token → 200
 *       {@code {ok:true, scope:"guarded"}}。验证「读放行 / 写拒绝未登录」对称分类。</li>
 * </ul>
 * 注：api 链已 {@code csrf.disable()}，故无 token 的 POST 直接命中鉴权门（401），非 CSRF 403。
 */
class GatewayPingControllerEndpointTest extends ApiIntegrationTest {

    /** 公开只读探测：游客无 token → 200，scope=public。 */
    @Test
    void publicPing_isOpenToGuest() throws Exception {
        mvc.perform(get("/api/v1/public/_ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.scope").value("public"));
    }

    /** 受保护写探测：缺 token → 401（写拒绝未登录）。 */
    @Test
    void guardedPing_withoutToken_is401() throws Exception {
        mvc.perform(post("/api/v1/_guarded-ping"))
                .andExpect(status().isUnauthorized());
    }

    /** 受保护写探测：带合法 token → 200，scope=guarded。 */
    @Test
    void guardedPing_withToken_is200() throws Exception {
        User u = newUser();
        mvc.perform(post("/api/v1/_guarded-ping")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.scope").value("guarded"));
    }
}
