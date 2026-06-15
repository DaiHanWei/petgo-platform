package com.tailtopia.shared.im.web;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import com.tailtopia.vet.domain.VetAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * {@link ImUserSigController} 集成测试（L1，真 Spring + 安全链 + IM stub）。
 *
 * <p>{@code GET /api/v1/im/usersig}：需 JWT。dev 默认 {@code petgo.im.mode=stub} →
 * {@link com.tailtopia.shared.im.StubTencentImClient} 签占位 UserSig（前缀 {@code stub-usersig-}）。
 *
 * <p>覆盖 5.5 live 增量的<b>用户态 MAU 闸门矩阵</b>：USER 有进行中会话→200；USER 无活跃会话→403；
 * VET 恒签→200；缺 token→401。
 */
class ImUserSigControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private VetTestSupport vets;

    @Test
    void userWithInProgressSessionGets200() throws Exception {
        User actor = newUser();
        VetAccount vet = vets.newActiveVet("接单医生");
        // 已接单进行中会话 → 用户进入 IM 登录闸门放行。
        vets.newInProgressSession(actor.getId(), vet.getId());

        mvc.perform(get("/api/v1/im/usersig")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imUserId", is("u_" + actor.getId())))
                .andExpect(jsonPath("$.userSig", is("stub-usersig-u_" + actor.getId())))
                .andExpect(jsonPath("$.expireSeconds", notNullValue()));
    }

    @Test
    void userWithoutActiveSessionGets403() throws Exception {
        // 无任何进行中/待关闭会话的用户 → MAU 闸门拒签，403。
        User actor = newUser();

        mvc.perform(get("/api/v1/im/usersig")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void vetGets200WithoutSessionCheck() throws Exception {
        VetAccount vet = vets.newActiveVet("恒签医生");

        mvc.perform(get("/api/v1/im/usersig")
                        .header(HttpHeaders.AUTHORIZATION, vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imUserId", is("v_" + vet.getId())))
                .andExpect(jsonPath("$.userSig", is("stub-usersig-v_" + vet.getId())));
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mvc.perform(get("/api/v1/im/usersig"))
                .andExpect(status().isUnauthorized());
    }
}
