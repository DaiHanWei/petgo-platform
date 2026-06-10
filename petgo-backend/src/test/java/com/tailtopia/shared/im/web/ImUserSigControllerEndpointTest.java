package com.petgo.shared.im.web;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgo.auth.domain.User;
import com.petgo.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * {@link ImUserSigController} 集成测试（L1，真 Spring + 安全链 + IM stub）。
 *
 * <p>{@code GET /api/v1/im/usersig}：需 JWT。dev 默认 {@code petgo.im.mode=stub} →
 * {@link com.petgo.shared.im.StubTencentImClient} 签占位 UserSig（前缀 {@code stub-usersig-}，非真实凭证）。
 *
 * <p>覆盖：USER 取 UserSig→200 + 字段（imUserId 映射 {@code u_<userId>}、stub userSig）；缺 token→401。
 */
class ImUserSigControllerEndpointTest extends ApiIntegrationTest {

    @Test
    void userGetsStubUserSigReturns200() throws Exception {
        User actor = newUser();

        mvc.perform(get("/api/v1/im/usersig")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId())))
                .andExpect(status().isOk())
                // ImAccountMapper.userImId：u_<userId>。
                .andExpect(jsonPath("$.imUserId", is("u_" + actor.getId())))
                // stub 占位凭证（前缀 stub-usersig-），非真实 IM 登录凭证。
                .andExpect(jsonPath("$.userSig", is("stub-usersig-u_" + actor.getId())))
                .andExpect(jsonPath("$.expireSeconds", notNullValue()));
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mvc.perform(get("/api/v1/im/usersig"))
                .andExpect(status().isUnauthorized());
    }
}
