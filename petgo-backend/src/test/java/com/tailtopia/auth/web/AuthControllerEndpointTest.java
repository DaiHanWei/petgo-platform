package com.tailtopia.auth.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.support.ApiIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * {@link AuthController} 端点集成测试：
 * {@code POST /api/v1/auth/google}、{@code /refresh}、{@code /logout}（均放行，无需 JWT）。
 *
 * <p>dev profile 下 {@code DevGoogleTokenVerifier} 把任意 idToken 解析成固定测试身份，故 google
 * 登录传任意非空 idToken 即成功并返回真 JWT + profile，可借此贯穿登录 → refresh 轮换 → logout 链路。
 */
class AuthControllerEndpointTest extends ApiIntegrationTest {

    /** google 登录正常路径：返回 access/refresh + role=USER + 内嵌 profile（dev 桩固定身份）。 */
    @Test
    void googleLogin_returnsTokensAndProfile() throws Exception {
        mvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", "any-dev-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.profile.id").isNumber());
    }

    /** google 登录校验：缺 idToken（@NotBlank）→ 422 ProblemDetail。 */
    @Test
    void googleLogin_missingIdToken_is422() throws Exception {
        mvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    /** google 登录校验：idToken 为空白字符串（@NotBlank）→ 422。 */
    @Test
    void googleLogin_blankIdToken_is422() throws Exception {
        mvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", "   "))))
                .andExpect(status().isUnprocessableEntity());
    }

    /** refresh 正常路径：拿登录返回的 refresh 轮换 → 新 access + 新 refresh，且新旧 refresh 不同（防重放）。 */
    @Test
    void refresh_rotatesTokens() throws Exception {
        String loginBody = mvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", "x"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String refreshToken = json.readTree(loginBody).get("refreshToken").asString();

        String refreshedBody = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String newRefresh = json.readTree(refreshedBody).get("refreshToken").asString();

        org.junit.jupiter.api.Assertions.assertNotEquals(refreshToken, newRefresh,
                "轮换后 refresh 应为新句柄");
    }

    /** refresh 防重放：同一旧 refresh 轮换后再用 → 命中 revoked → 401。 */
    @Test
    void refresh_replayOldToken_is401() throws Exception {
        String loginBody = mvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", "x"))))
                .andReturn().getResponse().getContentAsString();
        String refreshToken = json.readTree(loginBody).get("refreshToken").asString();

        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk());

        // 旧句柄第二次使用 → 已 revoked → 401
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized());
    }

    /** refresh 未知句柄 → 401 ProblemDetail。 */
    @Test
    void refresh_unknownToken_is401() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", "totally-bogus-handle"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    /** refresh 校验：缺 refreshToken（@NotBlank）→ 422。 */
    @Test
    void refresh_missingToken_is422() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of())))
                .andExpect(status().isUnprocessableEntity());
    }

    /** logout 正常路径：作废 refresh 句柄 → 200，且作废后该 refresh 不可再轮换（401）。 */
    @Test
    void logout_revokesRefreshHandle() throws Exception {
        String loginBody = mvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("idToken", "x"))))
                .andReturn().getResponse().getContentAsString();
        String refreshToken = json.readTree(loginBody).get("refreshToken").asString();

        mvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk());

        // 退出后该 refresh 已作废 → 不可轮换
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized());
    }

    /** logout 幂等：未知/已失效句柄静默成功（200），退出不报错。 */
    @Test
    void logout_unknownHandle_isIdempotent200() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", "unknown-handle"))))
                .andExpect(status().isOk());
    }

    /** logout 校验：缺 refreshToken（@NotBlank）→ 422。 */
    @Test
    void logout_missingToken_is422() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of())))
                .andExpect(status().isUnprocessableEntity());
    }
}
