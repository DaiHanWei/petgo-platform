package com.tailtopia.profile.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.ArchiveDecision;
import com.tailtopia.profile.domain.HealthEvent;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.HealthEventRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * L1 集成测试：{@code /api/v1/health-events} 真 HTTP 链路（问诊存档决策 + 决策查询）。
 *
 * <p>实际端点（2 个）：{@code POST /archive-decisions}（记录决策，幂等）、{@code GET /decision?sourceRef=}
 * （查是否已决策）。归属校验：petId 须属当前用户 JWT，否则 403。健康事件落 {@code health_events}（ARCHIVED 展示，
 * SKIPPED 仅落决策）。为避免触达未接入的 IM fetcher（Epic 5），ARCHIVED 用例不带 {@code imImageRefs}。
 */
class HealthEventControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private PetProfileRepository profiles;

    @Autowired
    private HealthEventRepository healthEvents;

    @Autowired
    private CardTokenGenerator cardTokenGenerator;

    private static final AtomicLong REF_SEQ = new AtomicLong(System.nanoTime());

    /** 直接造一只属于 owner 的宠物档案，返回其 petId。 */
    private long createPetFor(User owner) {
        PetProfile p = PetProfile.create(owner.getId(), com.tailtopia.profile.domain.PetType.DOG,
                "旺财", null, "柴犬", null, null,
                cardTokenGenerator.generate());
        return profiles.save(p).getId();
    }

    private String uniqueRef(String prefix) {
        return prefix + "-" + REF_SEQ.incrementAndGet();
    }

    private String archiveBody(long petId, String sourceRef) {
        return """
                {"sourceType":"AI_TRIAGE","sourceRef":"%s","petId":%d,"decision":"ARCHIVED",
                 "symptomSummary":"打喷嚏","aiLevel":"GREEN","adviceSummary":"多观察"}
                """.formatted(sourceRef, petId);
    }

    // ---------- 记录存档决策 ----------

    @Test
    void archiveDecisionPersistsHealthEvent() throws Exception {
        User owner = newUser();
        long petId = createPetFor(owner);
        String ref = uniqueRef("ai");

        mvc.perform(post("/api/v1/health-events/archive-decisions")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(archiveBody(petId, ref)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceRef").value(ref))
                .andExpect(jsonPath("$.decision").value("ARCHIVED"))
                .andExpect(jsonPath("$.alreadyDecided").value(false));

        HealthEvent saved = healthEvents.findBySourceRef(ref).orElseThrow();
        Assertions.assertEquals(petId, saved.getPetId());
        Assertions.assertEquals(ArchiveDecision.ARCHIVED, saved.getArchiveDecision());
    }

    @Test
    void skippedDecisionPersistsButNoDisplay() throws Exception {
        User owner = newUser();
        long petId = createPetFor(owner);
        String ref = uniqueRef("skip");

        mvc.perform(post("/api/v1/health-events/archive-decisions")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceType":"VET_CONSULT","sourceRef":"%s","petId":%d,"decision":"SKIPPED"}
                                """.formatted(ref, petId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("SKIPPED"))
                .andExpect(jsonPath("$.alreadyDecided").value(false));

        HealthEvent saved = healthEvents.findBySourceRef(ref).orElseThrow();
        Assertions.assertEquals(ArchiveDecision.SKIPPED, saved.getArchiveDecision());
    }

    @Test
    void duplicateDecisionIsIdempotent() throws Exception {
        User owner = newUser();
        long petId = createPetFor(owner);
        String ref = uniqueRef("dup");
        String token = userBearer(owner.getId());

        mvc.perform(post("/api/v1/health-events/archive-decisions")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(archiveBody(petId, ref)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyDecided").value(false));

        // 同 sourceRef 再决策 → 幂等无操作，alreadyDecided=true。
        mvc.perform(post("/api/v1/health-events/archive-decisions")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceType":"AI_TRIAGE","sourceRef":"%s","petId":%d,"decision":"SKIPPED"}
                                """.formatted(ref, petId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyDecided").value(true));
    }

    @Test
    void archiveOnOthersPetIs403() throws Exception {
        User owner = newUser();
        User intruder = newUser();
        long petId = createPetFor(owner);

        // intruder 拿别人的 petId 写决策 → 归属校验失败 403。
        mvc.perform(post("/api/v1/health-events/archive-decisions")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(intruder.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(archiveBody(petId, uniqueRef("intrude"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void archiveOnNonexistentPetIs403() throws Exception {
        User owner = newUser();
        // owner 无任何宠物，petId 不存在 → ownsPet 为 false → 403。
        mvc.perform(post("/api/v1/health-events/archive-decisions")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(archiveBody(999_999_999L, uniqueRef("ghost"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void archiveMissingRequiredFieldIs422() throws Exception {
        User owner = newUser();
        long petId = createPetFor(owner);
        // 缺 decision（@NotNull）→ Bean 校验 422。
        mvc.perform(post("/api/v1/health-events/archive-decisions")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceType":"AI_TRIAGE","sourceRef":"%s","petId":%d}
                                """.formatted(uniqueRef("nodec"), petId)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void archiveBlankSourceRefIs422() throws Exception {
        User owner = newUser();
        long petId = createPetFor(owner);
        // sourceRef @NotBlank。
        mvc.perform(post("/api/v1/health-events/archive-decisions")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceType":"AI_TRIAGE","sourceRef":"","petId":%d,"decision":"SKIPPED"}
                                """.formatted(petId)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void archiveWithoutTokenIs401() throws Exception {
        mvc.perform(post("/api/v1/health-events/archive-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(archiveBody(1L, uniqueRef("noauth"))))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 查询决策 ----------

    @Test
    void hasDecisionReflectsPersistedState() throws Exception {
        User owner = newUser();
        long petId = createPetFor(owner);
        String ref = uniqueRef("query");
        String token = userBearer(owner.getId());

        // 未决策前 → false。
        mvc.perform(get("/api/v1/health-events/decision")
                        .param("sourceRef", ref)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decided").value(false));

        mvc.perform(post("/api/v1/health-events/archive-decisions")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(archiveBody(petId, ref)))
                .andExpect(status().isOk());

        // 决策后 → true。
        mvc.perform(get("/api/v1/health-events/decision")
                        .param("sourceRef", ref)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decided").value(true));
    }

    @Test
    void hasDecisionWithoutTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/health-events/decision")
                        .param("sourceRef", uniqueRef("noauth")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void hasDecisionEmptyParamReturnsFalse() throws Exception {
        User owner = newUser();
        // 空 sourceRef（非缺失）→ 仍登录通过，查无决策 → false。
        // 注：缺失 @RequestParam 会被 catch-all 异常处理器归 500（见疑似 bug 报告），故此处用空值而非省略。
        mvc.perform(get("/api/v1/health-events/decision")
                        .param("sourceRef", "")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decided").value(false));
    }
}
