package com.petgo.consult.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgo.auth.domain.User;
import com.petgo.consult.domain.ConsultSession;
import com.petgo.consult.domain.SessionStatus;
import com.petgo.consult.repository.ConsultSessionRepository;
import com.petgo.consult.service.ConsultQueueService;
import com.petgo.support.ApiIntegrationTest;
import com.petgo.support.VetTestSupport;
import com.petgo.vet.domain.VetAccount;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1 集成：{@link VetConsultController}（{@code /api/v1/vet/consult-sessions}，6 端点）。
 *
 * <ul>
 *   <li>{@code GET /waiting} 待接单列表（Redis 队列）。</li>
 *   <li>{@code POST /{id}/accept} 接单（CAS WAITING→IN_PROGRESS + IM 建会话 + 落库）。</li>
 *   <li>{@code POST /{id}/end} 兽医结束（IN_PROGRESS→PENDING_CLOSE，归属校验）。</li>
 *   <li>{@code POST /{id}/notify-reply} 回复通知（归属 + 进行中校验）。</li>
 *   <li>{@code GET /{id}} 会话视图。</li>
 *   <li>{@code GET /{id}/assist} AI 参考回复（冷启动历史空）。</li>
 * </ul>
 *
 * <p>角色门控：每端点至少一条 user→403 / 缺 token→401 / active vet→2xx。
 */
class VetConsultControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private VetTestSupport vets;

    @Autowired
    private ConsultSessionRepository sessions;

    @Autowired
    private ConsultQueueService queue;

    // ===== GET /waiting =====

    @Test
    void waiting_listsEnqueuedWaitingSessions() throws Exception {
        VetAccount vet = vets.newActiveVet("接单医生");
        User user = newUser();
        ConsultSession s = vets.newWaitingAiSession(
                user.getId(), "YELLOW", "幼犬呕吐两次", java.util.List.of("private/a.jpg"));
        queue.enqueue(s.getId());

        mvc.perform(get("/api/v1/vet/consult-sessions/waiting")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                // 至少能找到刚入队的这条（含 AI 摘要字段）
                .andExpect(jsonPath("$[?(@.sessionId == " + s.getId() + ")].source").value(
                        org.hamcrest.Matchers.hasItem("AI_UPGRADE")))
                .andExpect(jsonPath("$[?(@.sessionId == " + s.getId() + ")].aiDangerLevel").value(
                        org.hamcrest.Matchers.hasItem("YELLOW")));
    }

    @Test
    void waiting_userToken_isForbidden403() throws Exception {
        var user = newUser();
        mvc.perform(get("/api/v1/vet/consult-sessions/waiting")
                        .header("Authorization", userBearer(user.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void waiting_missingToken_isUnauthorized401() throws Exception {
        mvc.perform(get("/api/v1/vet/consult-sessions/waiting"))
                .andExpect(status().isUnauthorized());
    }

    // ===== POST /{id}/accept =====

    @Test
    void accept_transitionsToInProgressAndPersists() throws Exception {
        VetAccount vet = vets.newActiveVet("抢单医生");
        User user = newUser();
        ConsultSession s = vets.newWaitingSession(user.getId());
        queue.enqueue(s.getId());

        mvc.perform(post("/api/v1/vet/consult-sessions/" + s.getId() + "/accept")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(s.getId()))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.imConversationId").isNotEmpty());

        // 落库：状态流转 + 绑定 vet + IM 会话标识
        ConsultSession reloaded = vets.reload(s.getId());
        Assertions.assertThat(reloaded.getStatus()).isEqualTo(SessionStatus.IN_PROGRESS);
        Assertions.assertThat(reloaded.getVetId()).isEqualTo(vet.getId());
        Assertions.assertThat(reloaded.getImConversationId()).isNotBlank();
    }

    @Test
    void accept_alreadyAccepted_isConflict409() throws Exception {
        VetAccount first = vets.newActiveVet("先到医生");
        VetAccount second = vets.newActiveVet("后到医生");
        User user = newUser();
        ConsultSession s = vets.newWaitingSession(user.getId());
        queue.enqueue(s.getId());

        // 先接走
        mvc.perform(post("/api/v1/vet/consult-sessions/" + s.getId() + "/accept")
                        .header("Authorization", vetBearer(first.getId())))
                .andExpect(status().isOk());

        // 再接同一会话 → 已被接走（状态非 WAITING）→ 409
        mvc.perform(post("/api/v1/vet/consult-sessions/" + s.getId() + "/accept")
                        .header("Authorization", vetBearer(second.getId())))
                .andExpect(status().isConflict());
    }

    @Test
    void accept_unknownSession_isNotFound404() throws Exception {
        VetAccount vet = vets.newActiveVet("空号医生");
        mvc.perform(post("/api/v1/vet/consult-sessions/99000000001/accept")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void accept_userToken_isForbidden403() throws Exception {
        var user = newUser();
        ConsultSession s = vets.newWaitingSession(user.getId());
        mvc.perform(post("/api/v1/vet/consult-sessions/" + s.getId() + "/accept")
                        .header("Authorization", userBearer(user.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void accept_missingToken_isUnauthorized401() throws Exception {
        var user = newUser();
        ConsultSession s = vets.newWaitingSession(user.getId());
        mvc.perform(post("/api/v1/vet/consult-sessions/" + s.getId() + "/accept"))
                .andExpect(status().isUnauthorized());
    }

    // ===== POST /{id}/end =====

    @Test
    void end_transitionsInProgressToPendingClose() throws Exception {
        VetAccount vet = vets.newActiveVet("结束医生");
        User user = newUser();
        ConsultSession s = vets.newInProgressSession(user.getId(), vet.getId());

        mvc.perform(post("/api/v1/vet/consult-sessions/" + s.getId() + "/end")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CLOSE"));

        Assertions.assertThat(vets.reload(s.getId()).getStatus())
                .isEqualTo(SessionStatus.PENDING_CLOSE);
    }

    @Test
    void end_byNonOwnerVet_isForbidden403() throws Exception {
        VetAccount owner = vets.newActiveVet("会话主人");
        VetAccount other = vets.newActiveVet("路人医生");
        User user = newUser();
        ConsultSession s = vets.newInProgressSession(user.getId(), owner.getId());

        // 非归属兽医结束 → service 抛 forbidden → 403
        mvc.perform(post("/api/v1/vet/consult-sessions/" + s.getId() + "/end")
                        .header("Authorization", vetBearer(other.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void end_userToken_isForbidden403() throws Exception {
        var user = newUser();
        VetAccount vet = vets.newActiveVet("门控医生");
        ConsultSession s = vets.newInProgressSession(user.getId(), vet.getId());
        mvc.perform(post("/api/v1/vet/consult-sessions/" + s.getId() + "/end")
                        .header("Authorization", userBearer(user.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void end_missingToken_isUnauthorized401() throws Exception {
        mvc.perform(post("/api/v1/vet/consult-sessions/1/end"))
                .andExpect(status().isUnauthorized());
    }

    // ===== POST /{id}/notify-reply =====

    @Test
    void notifyReply_inProgress_succeeds() throws Exception {
        VetAccount vet = vets.newActiveVet("回复医生");
        User user = newUser();
        ConsultSession s = vets.newInProgressSession(user.getId(), vet.getId());

        mvc.perform(post("/api/v1/vet/consult-sessions/" + s.getId() + "/notify-reply")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk());
    }

    @Test
    void notifyReply_byNonOwnerVet_isForbidden403() throws Exception {
        VetAccount owner = vets.newActiveVet("回复主人");
        VetAccount other = vets.newActiveVet("回复路人");
        User user = newUser();
        ConsultSession s = vets.newInProgressSession(user.getId(), owner.getId());
        mvc.perform(post("/api/v1/vet/consult-sessions/" + s.getId() + "/notify-reply")
                        .header("Authorization", vetBearer(other.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void notifyReply_userToken_isForbidden403() throws Exception {
        var user = newUser();
        mvc.perform(post("/api/v1/vet/consult-sessions/1/notify-reply")
                        .header("Authorization", userBearer(user.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void notifyReply_missingToken_isUnauthorized401() throws Exception {
        mvc.perform(post("/api/v1/vet/consult-sessions/1/notify-reply"))
                .andExpect(status().isUnauthorized());
    }

    // ===== GET /{id} =====

    @Test
    void session_returnsView() throws Exception {
        VetAccount vet = vets.newActiveVet("视图医生");
        User user = newUser();
        ConsultSession s = vets.newInProgressSession(user.getId(), vet.getId());

        mvc.perform(get("/api/v1/vet/consult-sessions/" + s.getId())
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(s.getId()))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.userId").value(user.getId()));
    }

    @Test
    void session_unknown_isNotFound404() throws Exception {
        VetAccount vet = vets.newActiveVet("查空医生");
        mvc.perform(get("/api/v1/vet/consult-sessions/99000000002")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void session_userToken_isForbidden403() throws Exception {
        var user = newUser();
        mvc.perform(get("/api/v1/vet/consult-sessions/1")
                        .header("Authorization", userBearer(user.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void session_missingToken_isUnauthorized401() throws Exception {
        mvc.perform(get("/api/v1/vet/consult-sessions/1"))
                .andExpect(status().isUnauthorized());
    }

    // ===== GET /{id}/assist =====

    @Test
    void assist_returnsReferenceReplyAndEmptyHistory() throws Exception {
        VetAccount vet = vets.newActiveVet("辅助医生");
        User user = newUser();
        ConsultSession s = vets.newWaitingAiSession(
                user.getId(), "YELLOW", "持续打喷嚏", java.util.List.of());

        mvc.perform(get("/api/v1/vet/consult-sessions/" + s.getId() + "/assist")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiReferenceReply").isNotEmpty())
                // 冷启动历史摘要空（G-2）
                .andExpect(jsonPath("$.historySummaries").isArray())
                .andExpect(jsonPath("$.historySummaries").isEmpty());
    }

    @Test
    void assist_userToken_isForbidden403() throws Exception {
        var user = newUser();
        mvc.perform(get("/api/v1/vet/consult-sessions/1/assist")
                        .header("Authorization", userBearer(user.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void assist_missingToken_isUnauthorized401() throws Exception {
        mvc.perform(get("/api/v1/vet/consult-sessions/1/assist"))
                .andExpect(status().isUnauthorized());
    }
}
