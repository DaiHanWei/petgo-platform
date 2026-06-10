package com.petgo.auth.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgo.support.ApiIntegrationTest;
import com.petgo.vet.domain.VetAccount;
import com.petgo.vet.domain.VetStatus;
import com.petgo.vet.service.VetAccountService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * {@link VetAuthController} 端点集成测试：{@code POST /api/v1/auth/vet/login}（放行，无需 token）。
 *
 * <p>登录需 DB 存在 active vet 账号——经 {@link VetAccountService#create} 造（内部 BCrypt encode）。
 * 失败统一 401（不区分账号不存在/密码错/封禁，防枚举）。
 */
class VetAuthControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private VetAccountService vetAccounts;

    private VetAccount newActiveVet(String rawPassword) {
        long n = SEQ.incrementAndGet();
        return vetAccounts.create("兽医" + n, "vet-it-" + n, rawPassword);
    }

    /** 正常路径：active 账号 + 正确密码 → 200 + access/refresh + displayName + role=VET。 */
    @Test
    void vetLogin_validCredentials_returnsVetTokens() throws Exception {
        VetAccount vet = newActiveVet("secret-password-1");

        mvc.perform(post("/api/v1/auth/vet/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("username", vet.getUsername(), "password", "secret-password-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.displayName").value(vet.getDisplayName()))
                .andExpect(jsonPath("$.role").value("VET"));
    }

    /** 密码错误 → 401（文案不区分，防枚举）。 */
    @Test
    void vetLogin_wrongPassword_is401() throws Exception {
        VetAccount vet = newActiveVet("correct-password");

        mvc.perform(post("/api/v1/auth/vet/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("username", vet.getUsername(), "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    /** 账号不存在 → 401（与密码错误同文案，防枚举）。 */
    @Test
    void vetLogin_unknownUsername_is401() throws Exception {
        mvc.perform(post("/api/v1/auth/vet/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("username", "no-such-vet-" + SEQ.incrementAndGet(),
                                        "password", "whatever-pass"))))
                .andExpect(status().isUnauthorized());
    }

    /** 被封禁账号 + 正确密码 → 401（BANNED 不可登录，文案不泄露封禁状态）。 */
    @Test
    void vetLogin_bannedAccount_is401() throws Exception {
        VetAccount vet = newActiveVet("banned-user-pass");
        vetAccounts.setStatus(vet.getId(), VetStatus.BANNED);

        mvc.perform(post("/api/v1/auth/vet/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("username", vet.getUsername(), "password", "banned-user-pass"))))
                .andExpect(status().isUnauthorized());
    }

    /** 校验：缺 username（@NotBlank）→ 422。 */
    @Test
    void vetLogin_missingUsername_is422() throws Exception {
        mvc.perform(post("/api/v1/auth/vet/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("password", "some-pass"))))
                .andExpect(status().isUnprocessableEntity());
    }

    /** 校验：缺 password（@NotBlank）→ 422。 */
    @Test
    void vetLogin_missingPassword_is422() throws Exception {
        mvc.perform(post("/api/v1/auth/vet/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "someone"))))
                .andExpect(status().isUnprocessableEntity());
    }
}
