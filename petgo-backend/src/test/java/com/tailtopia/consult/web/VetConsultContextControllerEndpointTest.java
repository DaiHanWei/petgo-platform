package com.petgo.consult.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgo.auth.domain.User;
import com.petgo.consult.domain.ConsultSession;
import com.petgo.support.ApiIntegrationTest;
import com.petgo.support.VetTestSupport;
import com.petgo.vet.domain.VetAccount;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1 集成：{@link VetConsultContextController}
 * （{@code GET /api/v1/vet/consult-sessions/{id}/ai-context}）。
 *
 * <p>覆盖：DIRECT 会话 → {@code hasAiContext=false}（前端不渲染上下文卡）；AI_UPGRADE 会话 →
 * 评级/描述回显（图引用空时不触发签名，避免依赖真实 OSS 凭证）；角色门控 user→403 / 缺 token→401。
 */
class VetConsultContextControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private VetTestSupport vets;

    @Test
    void directSession_hasNoAiContext() throws Exception {
        VetAccount vet = vets.newActiveVet("上下文医生1");
        User user = newUser();
        ConsultSession s = vets.newWaitingSession(user.getId());

        mvc.perform(get("/api/v1/vet/consult-sessions/" + s.getId() + "/ai-context")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAiContext").value(false))
                .andExpect(jsonPath("$.dangerLevel").doesNotExist())
                .andExpect(jsonPath("$.imageUrls").isEmpty());
    }

    @Test
    void aiUpgradeSession_returnsContext() throws Exception {
        VetAccount vet = vets.newActiveVet("上下文医生2");
        User user = newUser();
        // 图引用空 → service 不调 SignedUrlService（不依赖真实 OSS 凭证）
        ConsultSession s = vets.newWaitingAiSession(user.getId(), "GREEN", "轻微皮肤瘙痒", List.of());

        mvc.perform(get("/api/v1/vet/consult-sessions/" + s.getId() + "/ai-context")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAiContext").value(true))
                .andExpect(jsonPath("$.dangerLevel").value("GREEN"))
                .andExpect(jsonPath("$.symptomText").value("轻微皮肤瘙痒"))
                .andExpect(jsonPath("$.imageUrls").isArray());
    }

    @Test
    void unknownSession_isNotFound404() throws Exception {
        VetAccount vet = vets.newActiveVet("上下文空号");
        mvc.perform(get("/api/v1/vet/consult-sessions/99000000003/ai-context")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void userToken_isForbidden403() throws Exception {
        var user = newUser();
        mvc.perform(get("/api/v1/vet/consult-sessions/1/ai-context")
                        .header("Authorization", userBearer(user.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingToken_isUnauthorized401() throws Exception {
        mvc.perform(get("/api/v1/vet/consult-sessions/1/ai-context"))
                .andExpect(status().isUnauthorized());
    }
}
