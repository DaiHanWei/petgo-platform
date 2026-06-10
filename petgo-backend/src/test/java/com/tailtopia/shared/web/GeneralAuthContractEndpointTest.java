package com.tailtopia.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 通用鉴权 / 错误契约集成测试（跨端点的横切保证，放 {@code com.tailtopia.shared.web}）。
 *
 * <p>覆盖三类平台级契约：
 * <ol>
 *   <li><b>鉴权门</b>：需鉴权端点（{@code GET /api/v1/me}）缺 token / 坏 token → 401，
 *       且响应为 {@code application/problem+json}（安全层统一信封）。</li>
 *   <li><b>角色门控</b>：USER token 打 VET 专属路径（{@code GET /api/v1/vet/me}）→ 403 ProblemDetail
 *       （双向门控：user/guest 越权拒绝）。</li>
 *   <li><b>未知路径</b>：携合法 token 的 {@code GET /api/v1/nope} → 404（鉴权通过后才暴露找不到）。</li>
 * </ol>
 *
 * <p>注：安全层 401/403 信封（{@code ProblemDetailAuthHandlers}）输出 type/title/status/detail/instance，
 * 与业务层 {@code GlobalExceptionHandler} 的 traceId 信封并非同一处——本测试对安全层路径不强断言 traceId。
 */
class GeneralAuthContractEndpointTest extends ApiIntegrationTest {

    /** 缺 token 打需鉴权端点 → 401 + problem+json + 统一信封字段。 */
    @Test
    void protectedEndpoint_withoutToken_is401Problem() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.type").value("https://petgo/errors/unauthorized"))
                .andExpect(jsonPath("$.instance").value("/api/v1/me"));
    }

    /** 坏 token（无法解码的乱串）→ 401（资源服务器 JWT 解码失败）。 */
    @Test
    void protectedEndpoint_withGarbageToken_is401() throws Exception {
        mvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-jwt-xxx.yyy.zzz"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
    }

    /** 角色门控：USER token 打 VET 专属路径 → 403 ProblemDetail（越权，非 401）。 */
    @Test
    void userToken_onVetPath_is403Problem() throws Exception {
        User u = newUser();
        mvc.perform(get("/api/v1/vet/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.type").value("https://petgo/errors/forbidden"));
    }

    /** 未知路径：携合法 token（先过鉴权门）打不存在的 /api/v1/nope → 404。 */
    @Test
    void unknownPath_withToken_is404() throws Exception {
        User u = newUser();
        mvc.perform(get("/api/v1/nope")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isNotFound());
    }
}
