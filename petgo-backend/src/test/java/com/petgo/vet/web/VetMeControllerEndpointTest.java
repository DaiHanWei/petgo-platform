package com.petgo.vet.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgo.support.ApiIntegrationTest;
import com.petgo.support.VetTestSupport;
import com.petgo.vet.domain.VetAccount;
import org.junit.jupiter.api.Test;

/**
 * L1 集成：{@link VetMeController}（{@code GET /api/v1/vet/me}）。
 *
 * <p>覆盖角色门控（active vet → 200，user token → 403，缺 token → 401）+ 自身视图字段
 * （绝不含 username/passwordHash）。BannedVetFilter 对 BANNED vet → 401 在
 * {@link VetPresenceControllerEndpointTest} 集中断言（任一 vet 端点均经同一 filter）。
 */
class VetMeControllerEndpointTest extends ApiIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private VetTestSupport vets;

    @Test
    void activeVet_returnsOwnView() throws Exception {
        VetAccount vet = vets.newActiveVet("王医生");

        mvc.perform(get("/api/v1/vet/me").header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(vet.getId()))
                .andExpect(jsonPath("$.displayName").value("王医生"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                // 绝不外泄登录账号 / 密码哈希
                .andExpect(jsonPath("$.username").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void userToken_isForbidden403() throws Exception {
        var user = newUser();
        mvc.perform(get("/api/v1/vet/me").header("Authorization", userBearer(user.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingToken_isUnauthorized401() throws Exception {
        mvc.perform(get("/api/v1/vet/me"))
                .andExpect(status().isUnauthorized());
    }
}
