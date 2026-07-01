package com.tailtopia.consult.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.consult.domain.ConsultRating;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.repository.ConsultRatingRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.consult.service.ConsultQueueService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import com.tailtopia.vet.domain.VetAccount;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

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

    @Autowired
    private PetProfileRepository petProfiles;

    @Autowired
    private ConsultRatingRepository ratings;

    /** 给用户造一只宠物（富化断言用）；cardToken 唯一。 */
    private PetProfile newPet(long ownerId, PetType type, String name, LocalDate birthday) {
        return petProfiles.save(PetProfile.create(
                ownerId, type, name, null, null, birthday, null, "card-it-" + SEQ.incrementAndGet()));
    }

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

    /**
     * 结束问诊请求体（Story C：必填最终诊断）。
     * 须发**全部 8 个字段**——客户端 {@code VetDiagnosisDraft.toJson()} 即如此。
     * 全字段必填（needsMedication=false 时 medName/medFrequency 可空）：此处填满（不需用药）作合法基线。
     */
    private static final String END_BODY = "{\"diagnosis\":\"Gastritis ringan\","
            + "\"generalAdvice\":\"Banyak istirahat\",\"needsMedication\":false,"
            + "\"medName\":null,\"medFrequency\":null,\"followUp\":\"Kontrol 3 hari\","
            + "\"worseningSigns\":\"Muntah terus\",\"clinicWithin\":\"24 jam\"}";

    @Test
    void end_transitionsInProgressToPendingClose() throws Exception {
        VetAccount vet = vets.newActiveVet("结束医生");
        User user = newUser();
        ConsultSession s = vets.newInProgressSession(user.getId(), vet.getId());

        mvc.perform(post("/api/v1/vet/consult-sessions/" + s.getId() + "/end")
                        .header("Authorization", vetBearer(vet.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(END_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CLOSE"));

        Assertions.assertThat(vets.reload(s.getId()).getStatus())
                .isEqualTo(SessionStatus.PENDING_CLOSE);
    }

    @Test
    void end_blankRequiredField_returns422() throws Exception {
        VetAccount vet = vets.newActiveVet("缺字段医生");
        User user = newUser();
        ConsultSession s = vets.newInProgressSession(user.getId(), vet.getId());
        // clinicWithin 空 → 全字段必填校验 422，不结束。
        String body = "{\"diagnosis\":\"Gastritis ringan\",\"generalAdvice\":\"Istirahat\","
                + "\"needsMedication\":false,\"medName\":null,\"medFrequency\":null,"
                + "\"followUp\":\"Kontrol 3 hari\",\"worseningSigns\":\"Muntah terus\",\"clinicWithin\":\"\"}";
        mvc.perform(post("/api/v1/vet/consult-sessions/" + s.getId() + "/end")
                        .header("Authorization", vetBearer(vet.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
        Assertions.assertThat(vets.reload(s.getId()).getStatus()).isEqualTo(SessionStatus.IN_PROGRESS);
    }

    @Test
    void end_needsMedicationButBlankMedDetails_returns422() throws Exception {
        VetAccount vet = vets.newActiveVet("缺药名医生");
        User user = newUser();
        ConsultSession s = vets.newInProgressSession(user.getId(), vet.getId());
        // 需用药但药名/频次空 → 跨字段校验 422。
        String body = "{\"diagnosis\":\"Infeksi\",\"generalAdvice\":\"Istirahat\","
                + "\"needsMedication\":true,\"medName\":\"\",\"medFrequency\":\"\","
                + "\"followUp\":\"Kontrol 3 hari\",\"worseningSigns\":\"Lemas\",\"clinicWithin\":\"24 jam\"}";
        mvc.perform(post("/api/v1/vet/consult-sessions/" + s.getId() + "/end")
                        .header("Authorization", vetBearer(vet.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void end_byNonOwnerVet_isForbidden403() throws Exception {
        VetAccount owner = vets.newActiveVet("会话主人");
        VetAccount other = vets.newActiveVet("路人医生");
        User user = newUser();
        ConsultSession s = vets.newInProgressSession(user.getId(), owner.getId());

        // 非归属兽医结束 → service 抛 forbidden → 403（带完整诊断 body 越过反序列化/校验，触发归属判定）
        mvc.perform(post("/api/v1/vet/consult-sessions/" + s.getId() + "/end")
                        .header("Authorization", vetBearer(other.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(END_BODY))
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

    // ===== 宠物身份富化（待接单 / 会话视图）=====

    @Test
    void waiting_enrichesPetIdentityAndOwnerHandle() throws Exception {
        VetAccount vet = vets.newActiveVet("富化医生");
        User user = newUser();
        newPet(user.getId(), PetType.CAT, "Oyen", LocalDate.now(ZoneOffset.UTC).minusMonths(24));
        ConsultSession s = vets.newWaitingAiSession(
                user.getId(), "YELLOW", "呕吐", java.util.List.of());
        queue.enqueue(s.getId());

        mvc.perform(get("/api/v1/vet/consult-sessions/waiting")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sessionId == " + s.getId() + ")].petName")
                        .value(org.hamcrest.Matchers.hasItem("Oyen")))
                .andExpect(jsonPath("$[?(@.sessionId == " + s.getId() + ")].petSpecies")
                        .value(org.hamcrest.Matchers.hasItem("CAT")))
                .andExpect(jsonPath("$[?(@.sessionId == " + s.getId() + ")].ownerHandle")
                        .value(org.hamcrest.Matchers.hasItem(user.getNickname())));
    }

    @Test
    void session_enrichesPetIdentity() throws Exception {
        VetAccount vet = vets.newActiveVet("视图富化医生");
        User user = newUser();
        newPet(user.getId(), PetType.DOG, "Milo", LocalDate.now(ZoneOffset.UTC).minusMonths(18));
        ConsultSession s = vets.newInProgressSession(user.getId(), vet.getId());

        mvc.perform(get("/api/v1/vet/consult-sessions/" + s.getId())
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.petName").value("Milo"))
                .andExpect(jsonPath("$.petSpecies").value("DOG"))
                .andExpect(jsonPath("$.petAgeMonths").value(18))
                .andExpect(jsonPath("$.ownerHandle").value(user.getNickname()));
    }

    // ===== GET /in-progress =====

    @Test
    void inProgress_listsVetActiveSessions() throws Exception {
        VetAccount vet = vets.newActiveVet("进行中医生");
        User user = newUser();
        newPet(user.getId(), PetType.CAT, "Mochi", LocalDate.now(ZoneOffset.UTC).minusMonths(8));
        ConsultSession s = vets.newInProgressSession(user.getId(), vet.getId());

        mvc.perform(get("/api/v1/vet/consult-sessions/in-progress")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sessionId == " + s.getId() + ")].petName")
                        .value(org.hamcrest.Matchers.hasItem("Mochi")))
                // 客户端按 userId 拼 IM 对端账号回查未读，必须下发。
                .andExpect(jsonPath("$[?(@.sessionId == " + s.getId() + ")].userId")
                        .value(org.hamcrest.Matchers.hasItem(user.getId().intValue())));
    }

    /**
     * Bug 回归：兽医结束（PENDING_CLOSE）后不再算「进行中」——否则同一用户发起新咨询后，
     * 会与新 IN_PROGRESS 卡叠成两条（重复 item）。PENDING_CLOSE 归历史，进行中只剩新会话。
     */
    @Test
    void inProgress_excludesPendingClose_noDuplicatePerUser() throws Exception {
        VetAccount vet = vets.newActiveVet("去重医生");
        User user = newUser();
        ConsultSession pendingClose = vets.newPendingCloseSession(user.getId(), vet.getId());
        ConsultSession active = vets.newInProgressSession(user.getId(), vet.getId());

        mvc.perform(get("/api/v1/vet/consult-sessions/in-progress")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sessionId == " + active.getId() + ")]")
                        .value(org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[?(@.sessionId == " + pendingClose.getId() + ")]")
                        .value(org.hamcrest.Matchers.empty()));
    }

    @Test
    void inProgress_userToken_isForbidden403() throws Exception {
        var user = newUser();
        mvc.perform(get("/api/v1/vet/consult-sessions/in-progress")
                        .header("Authorization", userBearer(user.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void inProgress_missingToken_isUnauthorized401() throws Exception {
        mvc.perform(get("/api/v1/vet/consult-sessions/in-progress"))
                .andExpect(status().isUnauthorized());
    }

    // ===== GET /history =====

    @Test
    void history_listsTerminalSessionsWithRating() throws Exception {
        VetAccount vet = vets.newActiveVet("历史医生");
        User user = newUser();
        ConsultSession s = vets.newClosedSession(user.getId(), vet.getId());
        ratings.save(ConsultRating.of(s.getId(), vet.getId(), user.getId(), 5, "讲解很清楚"));

        mvc.perform(get("/api/v1/vet/consult-sessions/history")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sessionId == " + s.getId() + ")].terminalState")
                        .value(org.hamcrest.Matchers.hasItem("CLOSED")))
                .andExpect(jsonPath("$[?(@.sessionId == " + s.getId() + ")].stars")
                        .value(org.hamcrest.Matchers.hasItem(5)))
                .andExpect(jsonPath("$[?(@.sessionId == " + s.getId() + ")].reviewText")
                        .value(org.hamcrest.Matchers.hasItem("讲解很清楚")));
    }

    /** Bug 回归：PENDING_CLOSE（兽医已结束、评分门窗口）归入兽医历史，终态标记 PENDING_CLOSE。 */
    @Test
    void history_includesPendingClose() throws Exception {
        VetAccount vet = vets.newActiveVet("待评分医生");
        User user = newUser();
        ConsultSession s = vets.newPendingCloseSession(user.getId(), vet.getId());

        mvc.perform(get("/api/v1/vet/consult-sessions/history")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sessionId == " + s.getId() + ")].terminalState")
                        .value(org.hamcrest.Matchers.hasItem("PENDING_CLOSE")));
    }

    @Test
    void history_userToken_isForbidden403() throws Exception {
        var user = newUser();
        mvc.perform(get("/api/v1/vet/consult-sessions/history")
                        .header("Authorization", userBearer(user.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void history_missingToken_isUnauthorized401() throws Exception {
        mvc.perform(get("/api/v1/vet/consult-sessions/history"))
                .andExpect(status().isUnauthorized());
    }

    // ===== GET /{id}/diagnosis（Bug 20260701-196：历史卡 View 只读诊断入口）=====

    /** 归属兽医结束后可读回自己定格的最终诊断（走真实写路径：先 /end 落诊断，再 GET）。 */
    @Test
    void diagnosis_ownerVetAfterEnd_returnsRecordedDiagnosis() throws Exception {
        VetAccount vet = vets.newActiveVet("回看医生");
        User user = newUser();
        ConsultSession s = vets.newInProgressSession(user.getId(), vet.getId());
        mvc.perform(post("/api/v1/vet/consult-sessions/" + s.getId() + "/end")
                        .header("Authorization", vetBearer(vet.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(END_BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/vet/consult-sessions/" + s.getId() + "/diagnosis")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnosis").value("Gastritis ringan"))
                .andExpect(jsonPath("$.followUp").value("Kontrol 3 hari"))
                .andExpect(jsonPath("$.clinicWithin").value("24 jam"));
    }

    /** 未出诊断（会话进行中、尚未结束）→ 204 No Content（前端转空态）。 */
    @Test
    void diagnosis_noDiagnosisYet_returns204() throws Exception {
        VetAccount vet = vets.newActiveVet("无诊断医生");
        User user = newUser();
        ConsultSession s = vets.newInProgressSession(user.getId(), vet.getId());

        mvc.perform(get("/api/v1/vet/consult-sessions/" + s.getId() + "/diagnosis")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isNoContent());
    }

    /** 非本会话接诊兽医查诊断 → 403（不越权看他人病例）。 */
    @Test
    void diagnosis_byNonOwnerVet_isForbidden403() throws Exception {
        VetAccount owner = vets.newActiveVet("诊断主人");
        VetAccount other = vets.newActiveVet("诊断路人");
        User user = newUser();
        ConsultSession s = vets.newInProgressSession(user.getId(), owner.getId());

        mvc.perform(get("/api/v1/vet/consult-sessions/" + s.getId() + "/diagnosis")
                        .header("Authorization", vetBearer(other.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void diagnosis_unknownSession_isNotFound404() throws Exception {
        VetAccount vet = vets.newActiveVet("查空诊断医生");
        mvc.perform(get("/api/v1/vet/consult-sessions/99000000003/diagnosis")
                        .header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void diagnosis_userToken_isForbidden403() throws Exception {
        var user = newUser();
        mvc.perform(get("/api/v1/vet/consult-sessions/1/diagnosis")
                        .header("Authorization", userBearer(user.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void diagnosis_missingToken_isUnauthorized401() throws Exception {
        mvc.perform(get("/api/v1/vet/consult-sessions/1/diagnosis"))
                .andExpect(status().isUnauthorized());
    }
}
