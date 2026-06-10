package com.petgo.shared.im.web;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgo.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link ImCallbackController} 集成测试（L1，真 Spring + 安全链）。
 *
 * <p>{@code POST /im/callback}：安全层放行（外部腾讯 IM 回调），但控制器内部经
 * {@link com.petgo.shared.im.TencentImClient#verifyCallback} 校验 token——非法来源拒绝（403）。
 * 配 {@code petgo.im.callback-token} 使 stub 走真实比对（默认空 token 时 stub 放行，无法验拒绝路径）。
 *
 * <p>覆盖：放行端点（无需 JWT）；正确 token→200 + {@code ActionStatus=OK}；缺/错 token→403。
 */
@TestPropertySource(properties = "petgo.im.callback-token=it-callback-secret")
class ImCallbackControllerEndpointTest extends ApiIntegrationTest {

    private static final String EVENT_BODY =
            "{\"CallbackCommand\":\"C2C.CallbackAfterSendMsg\",\"From_Account\":\"u_1\"}";

    @Test
    void correctTokenIsAcceptedReturnsOk() throws Exception {
        mvc.perform(post("/im/callback")
                        .param("token", "it-callback-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ActionStatus", is("OK")))
                .andExpect(jsonPath("$.ErrorCode", is(0)));
    }

    @Test
    void wrongTokenIsRejectedWith403() throws Exception {
        mvc.perform(post("/im/callback")
                        .param("token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingTokenIsRejectedWith403() throws Exception {
        mvc.perform(post("/im/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void endpointIsPermittedWithoutJwt() throws Exception {
        // 放行验证：缺 JWT 不返回 401（安全层放行），拒绝来自控制器内 token 校验（403），非鉴权 401。
        mvc.perform(post("/im/callback")
                        .param("token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_BODY))
                .andExpect(status().isForbidden());
    }
}
