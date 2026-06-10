package com.tailtopia.account.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.auth.domain.PetStatus;
import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * {@link AccountController} 端点集成测试：{@code DELETE /api/v1/me}（双重确认 → 202 异步注销）。
 *
 * <p>仅作用于 JWT {@code sub} 本人。确认短语须等于 {@code "确认注销"}（{@link DeleteAccountRequest}），
 * 缺失/不匹配 → 422。受理后登记 PENDING 注销作业（断言落库），异步级联在事务提交后执行。
 */
class AccountControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private AccountDeletionRepository deletions;

    /** 正常路径：本人 token + 正确确认短语 → 202，并断言已登记本人的注销作业（落库）。 */
    @Test
    void deleteMe_confirmed_is202_andRegistersDeletion() throws Exception {
        User u = newUser(PetStatus.HAS_PET);

        mvc.perform(delete("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmation", "确认注销"))))
                .andExpect(status().isAccepted());

        Assertions.assertTrue(deletions.findByUserId(u.getId()).isPresent(),
                "受理注销后应登记该用户的注销作业行");
    }

    /** 校验：确认短语错误 → 422，且不登记注销作业。 */
    @Test
    void deleteMe_wrongPhrase_is422_andNoDeletion() throws Exception {
        User u = newUser(PetStatus.HAS_PET);

        mvc.perform(delete("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmation", "随便写的"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));

        Assertions.assertTrue(deletions.findByUserId(u.getId()).isEmpty(),
                "确认短语错误不应登记注销作业");
    }

    /** 校验：缺 body（req == null）→ 422。 */
    @Test
    void deleteMe_missingBody_is422() throws Exception {
        User u = newUser(PetStatus.HAS_PET);

        mvc.perform(delete("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isUnprocessableEntity());

        Assertions.assertTrue(deletions.findByUserId(u.getId()).isEmpty());
    }

    /** 鉴权：缺 token → 401（无论 body 如何）。 */
    @Test
    void deleteMe_withoutToken_is401() throws Exception {
        mvc.perform(delete("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmation", "确认注销"))))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 防越权：A 的 token 调 DELETE /me 只受理 A 自己的注销——端点作用对象恒为 JWT sub，
     * 绝不触发 B 的注销。断言仅 A 有注销作业、B 无。
     */
    @Test
    void deleteMe_onlyAffectsTokenSubject_notOtherUser() throws Exception {
        User a = newUser(PetStatus.HAS_PET);
        User b = newUser(PetStatus.PLANNING);

        mvc.perform(delete("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmation", "确认注销"))))
                .andExpect(status().isAccepted());

        Assertions.assertTrue(deletions.findByUserId(a.getId()).isPresent(), "A 应被受理注销");
        Assertions.assertTrue(deletions.findByUserId(b.getId()).isEmpty(), "B 不应被 A 的请求注销");
    }
}
