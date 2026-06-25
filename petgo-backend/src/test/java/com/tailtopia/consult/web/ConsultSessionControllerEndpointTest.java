package com.tailtopia.consult.web;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.repository.VetAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * {@code /api/v1/consult-sessions}（8 端点）集成测试：创建/查询/active/继续等待/取消/评分/补弹门控。
 *
 * <p>覆盖：ROLE_USER 门控（vet→403 / guest→401）、状态机驱动（造 PENDING_CLOSE 评分）、
 * 评分校验（星级范围、重复评分、非本人）、越权（A 取不到 B 的会话）。
 */
class ConsultSessionControllerEndpointTest extends ApiIntegrationTest {

    private static final String BASE = "/api/v1/consult-sessions";

    @Autowired
    private ConsultSessionRepository sessions;

    @Autowired
    private VetAccountRepository vets;

    // ===== 创建会话 =====

    @Test
    void create_byUser_returnsWaitingSessionAndPersists() throws Exception {
        User u = newUser();
        String body = mvc.perform(post(BASE)
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("WAITING")))
                .andExpect(jsonPath("$.source", is("DIRECT")))
                .andExpect(jsonPath("$.alreadyActive", is(false)))
                .andReturn().getResponse().getContentAsString();

        long id = json.readTree(body).get("id").asLong();
        ConsultSession persisted = sessions.findById(id).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(SessionStatus.WAITING, persisted.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(u.getId(), persisted.getUserId());
    }

    @Test
    void create_withNullBody_defaultsToDirect() throws Exception {
        User u = newUser();
        mvc.perform(post(BASE).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source", is("DIRECT")));
    }

    @Test
    void create_whenActiveExists_returnsExistingWithAlreadyActive() throws Exception {
        User u = newUser();
        ConsultSession existing = sessions.save(ConsultSession.startWaiting(u.getId(), ConsultSource.DIRECT));

        mvc.perform(post(BASE).header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(existing.getId().intValue())))
                .andExpect(jsonPath("$.alreadyActive", is(true)));
    }

    @Test
    void create_aiUpgradeWithoutTriageTaskId_returns422() throws Exception {
        User u = newUser();
        mvc.perform(post(BASE).header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"AI_UPGRADE\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void create_missingToken_returns401() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_byVet_returns403() throws Exception {
        VetAccount vet = vets.save(VetAccount.create("vet-it-" + SEQ.incrementAndGet(),
                "$2a$10$abcdefghijklmnopqrstuv", "兽医"));
        mvc.perform(post(BASE).header("Authorization", vetBearer(vet.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    // ===== 查询 / active / 继续等待 =====

    @Test
    void get_ownSession_returns200() throws Exception {
        User u = newUser();
        ConsultSession s = sessions.save(ConsultSession.startWaiting(u.getId(), ConsultSource.DIRECT));
        mvc.perform(get(BASE + "/" + s.getId()).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(s.getId().intValue())))
                .andExpect(jsonPath("$.status", is("WAITING")));
    }

    @Test
    void get_unknownSession_returns404() throws Exception {
        User u = newUser();
        mvc.perform(get(BASE + "/999999999").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_otherUsersSession_returns404_noLeak() throws Exception {
        User owner = newUser();
        User attacker = newUser();
        ConsultSession s = sessions.save(ConsultSession.startWaiting(owner.getId(), ConsultSource.DIRECT));
        mvc.perform(get(BASE + "/" + s.getId()).header("Authorization", userBearer(attacker.getId())))
                .andExpect(status().isNotFound());
    }

    // ===== 用户查看自己病例 GET /{id}/case =====

    @Test
    void case_ownSessionWithSymptom_returns200() throws Exception {
        User u = newUser();
        ConsultSession s = ConsultSession.startWaiting(u.getId(), ConsultSource.DIRECT);
        s.bindDirectCase("muntah busa putih 2x", java.util.List.of());
        s = sessions.save(s);
        mvc.perform(get(BASE + "/" + s.getId() + "/case").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAiContext", is(true)))
                .andExpect(jsonPath("$.symptomText", is("muntah busa putih 2x")));
    }

    @Test
    void case_otherUsersSession_returns404_noLeak() throws Exception {
        User owner = newUser();
        User attacker = newUser();
        ConsultSession s = ConsultSession.startWaiting(owner.getId(), ConsultSource.DIRECT);
        s.bindDirectCase("private symptom", java.util.List.of());
        s = sessions.save(s);
        mvc.perform(get(BASE + "/" + s.getId() + "/case").header("Authorization", userBearer(attacker.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void case_missingToken_returns401() throws Exception {
        mvc.perform(get(BASE + "/1/case")).andExpect(status().isUnauthorized());
    }

    @Test
    void active_whenNone_returns204() throws Exception {
        User u = newUser();
        mvc.perform(get(BASE + "/active").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isNoContent());
    }

    @Test
    void active_whenWaiting_returns200() throws Exception {
        User u = newUser();
        ConsultSession s = sessions.save(ConsultSession.startWaiting(u.getId(), ConsultSource.DIRECT));
        mvc.perform(get(BASE + "/active").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(s.getId().intValue())))
                .andExpect(jsonPath("$.alreadyActive", is(true)));
    }

    @Test
    void continueWaiting_resetsAndReturns200() throws Exception {
        User u = newUser();
        ConsultSession s = sessions.save(ConsultSession.startWaiting(u.getId(), ConsultSource.DIRECT));
        mvc.perform(patch(BASE + "/" + s.getId() + "/continue-waiting")
                        .header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("WAITING")));
    }

    // ===== 取消 =====

    @Test
    void cancel_waitingSession_transitionsToCancelled() throws Exception {
        User u = newUser();
        ConsultSession s = sessions.save(ConsultSession.startWaiting(u.getId(), ConsultSource.DIRECT));
        mvc.perform(delete(BASE + "/" + s.getId()).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")));

        org.junit.jupiter.api.Assertions.assertEquals(
                SessionStatus.CANCELLED, sessions.findById(s.getId()).orElseThrow().getStatus());
    }

    @Test
    void cancel_otherUsersSession_returns404() throws Exception {
        User owner = newUser();
        User attacker = newUser();
        ConsultSession s = sessions.save(ConsultSession.startWaiting(owner.getId(), ConsultSource.DIRECT));
        mvc.perform(delete(BASE + "/" + s.getId()).header("Authorization", userBearer(attacker.getId())))
                .andExpect(status().isNotFound());
    }

    // ===== 评分门 =====

    @Test
    void rate_pendingCloseSession_closesRated() throws Exception {
        User u = newUser();
        ConsultSession s = pendingClose(u.getId(), 4242L);
        mvc.perform(post(BASE + "/" + s.getId() + "/rating")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stars\":5,\"comment\":\"很专业\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CLOSED")))
                .andExpect(jsonPath("$.closedReason", is("RATED")))
                .andExpect(jsonPath("$.rated", is(true)));
    }

    @Test
    void rate_starsBelowRange_returns422() throws Exception {
        User u = newUser();
        ConsultSession s = pendingClose(u.getId(), 4243L);
        mvc.perform(post(BASE + "/" + s.getId() + "/rating")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stars\":0}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rate_starsAboveRange_returns422() throws Exception {
        User u = newUser();
        ConsultSession s = pendingClose(u.getId(), 4244L);
        mvc.perform(post(BASE + "/" + s.getId() + "/rating")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stars\":6}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rate_missingStars_returns422() throws Exception {
        User u = newUser();
        ConsultSession s = pendingClose(u.getId(), 4245L);
        mvc.perform(post(BASE + "/" + s.getId() + "/rating")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"无星级\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rate_commentTooLong_returns422() throws Exception {
        User u = newUser();
        ConsultSession s = pendingClose(u.getId(), 4246L);
        String longComment = "字".repeat(101);
        mvc.perform(post(BASE + "/" + s.getId() + "/rating")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stars\":3,\"comment\":\"" + longComment + "\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rate_duplicate_returns409() throws Exception {
        User u = newUser();
        ConsultSession s = pendingClose(u.getId(), 4247L);
        mvc.perform(post(BASE + "/" + s.getId() + "/rating")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stars\":5}"))
                .andExpect(status().isOk());
        // 第二次：会话已 CLOSED 且已有评分 → 409
        mvc.perform(post(BASE + "/" + s.getId() + "/rating")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stars\":4}"))
                .andExpect(status().isConflict());
    }

    @Test
    void rate_otherUsersSession_returns404() throws Exception {
        User owner = newUser();
        User attacker = newUser();
        ConsultSession s = pendingClose(owner.getId(), 4248L);
        mvc.perform(post(BASE + "/" + s.getId() + "/rating")
                        .header("Authorization", userBearer(attacker.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stars\":5}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rate_waitingSession_returns409_notRatable() throws Exception {
        User u = newUser();
        ConsultSession s = sessions.save(ConsultSession.startWaiting(u.getId(), ConsultSource.DIRECT));
        mvc.perform(post(BASE + "/" + s.getId() + "/rating")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stars\":5}"))
                .andExpect(status().isConflict());
    }

    // ===== 补弹评分 =====

    @Test
    void pendingRating_whenNone_returns204() throws Exception {
        User u = newUser();
        mvc.perform(get(BASE + "/pending-rating").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isNoContent());
    }

    @Test
    void pendingRating_whenUnratedClosed_returns200() throws Exception {
        User u = newUser();
        ConsultSession s = pendingClose(u.getId(), 4249L);
        s.closeUnrated(); // PENDING_CLOSE → CLOSED(UNRATED) + ratingPromptState=PENDING
        sessions.save(s);
        mvc.perform(get(BASE + "/pending-rating").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(s.getId().intValue())))
                .andExpect(jsonPath("$.closedReason", is("UNRATED")));
    }

    @Test
    void ratingPrompted_marksPromptedAnd200() throws Exception {
        User u = newUser();
        ConsultSession s = pendingClose(u.getId(), 4250L);
        s.closeUnrated();
        sessions.save(s);
        mvc.perform(patch(BASE + "/" + s.getId() + "/rating-prompted")
                        .header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk());
        // 标记后不再处于待补弹
        mvc.perform(get(BASE + "/pending-rating").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isNoContent());
    }

    @Test
    void ratingPrompted_missingToken_returns401() throws Exception {
        mvc.perform(patch(BASE + "/1/rating-prompted"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Bug 回归：CLOSED(UNRATED) 补评分后 GET 须报 {@code rated=true}（虽 closedReason 仍 UNRATED）——
     * 前端据此关闭评分入口，否则补评分后重进会再次显示评分、提交被 409。
     */
    @Test
    void get_afterLateRatingOnUnratedClosed_ratedTrue() throws Exception {
        User u = newUser();
        ConsultSession s = pendingClose(u.getId(), 4260L);
        s.closeUnrated(); // CLOSED(UNRATED) + ratingPromptState=PENDING
        sessions.save(s);
        // 补评分（CLOSED/UNRATED 仍可评）→ clearRatingPrompt 置 NONE，closedReason 不变。
        mvc.perform(post(BASE + "/" + s.getId() + "/rating")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stars\":4}"))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "/" + s.getId()).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedReason", is("UNRATED")))
                .andExpect(jsonPath("$.rated", is(true)));
    }

    /** 超时未评（CLOSED/UNRATED + PENDING）未评分 → rated=false，前端仍可补评分。 */
    @Test
    void get_unratedTimeoutClosed_ratedFalse() throws Exception {
        User u = newUser();
        ConsultSession s = pendingClose(u.getId(), 4261L);
        s.closeUnrated();
        sessions.save(s);
        mvc.perform(get(BASE + "/" + s.getId()).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rated", is(false)));
    }

    /** 造一条 PENDING_CLOSE 会话（驱动状态机 WAITING→IN_PROGRESS→PENDING_CLOSE）。 */
    private ConsultSession pendingClose(long userId, long vetId) {
        ConsultSession s = ConsultSession.startWaiting(userId, ConsultSource.DIRECT);
        s.markInProgress(vetId);
        s.endByVet();
        return sessions.save(s);
    }
}
