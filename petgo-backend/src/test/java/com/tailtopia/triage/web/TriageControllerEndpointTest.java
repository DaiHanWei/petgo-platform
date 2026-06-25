package com.tailtopia.triage.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.triage.domain.DangerLevel;
import com.tailtopia.triage.domain.TriageStatus;
import com.tailtopia.triage.domain.TriageTask;
import com.tailtopia.triage.dto.TriageSubmitRequest;
import com.tailtopia.triage.repository.TriageTaskRepository;
import com.tailtopia.triage.service.TriageEventListener;
import com.tailtopia.triage.service.TriageProcessor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * L1 集成测试：分诊端点 {@code POST/GET /api/v1/triage} 走完整安全链 + 真实落库（dev profile，Gemini stub）。
 *
 * <p>与既有 L0 {@link TriageControllerTest}（直 new 控制器 + mock service）互补：此层验证真实 HTTP
 * 行为——序列化、Bean 校验（422）、JWT 鉴权（401）、越权/不存在统一 403、Redis 限流（429），
 * 以及安全攸关：后置安全规则层对高危症状强制升红 RED（同步驱动 {@link TriageProcessor#process} 后断言）。
 */
class TriageControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private TriageProcessor triageProcessor;

    @Autowired
    private TriageTaskRepository triageTasks;

    // 隔离异步处理：submit 后的 @Async AFTER_COMMIT listener 会调 Gemini 改 status，
    // 在 CI(无 Gemini key)常于断言前跑完并置 FAILED → 与「断言受理即 PENDING」形成竞态。
    // mock 掉 listener 使受理后 status 稳定 PENDING；不影响其它同步直驱 TriageProcessor.process 的用例。
    @MockitoBean
    private TriageEventListener triageEventListener;

    private MvcResult submit(long userId, TriageSubmitRequest req) throws Exception {
        return mvc.perform(post("/api/v1/triage")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andReturn();
    }

    private long triageIdOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("triageId").asLong();
    }

    // ---------- 提交 ----------

    /** USER 提交症状（含图 objectKey）→ 202 + triageId，落库 PENDING；userId 取自 JWT。 */
    @Test
    void submitAcceptsAndPersistsPending() throws Exception {
        User u = newUser();

        MvcResult result = mvc.perform(post("/api/v1/triage")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new TriageSubmitRequest("最近有点咳嗽", List.of("priv/k1.jpg"), null))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.triageId").isNumber())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        long id = triageIdOf(result);
        TriageTask saved = triageTasks.findById(id).orElseThrow();
        // userId 取自 JWT（不信任客户端，请求体也未带 userId）。
        org.assertj.core.api.Assertions.assertThat(saved.getUserId()).isEqualTo(u.getId());
        org.assertj.core.api.Assertions.assertThat(saved.getStatus()).isEqualTo(TriageStatus.PENDING);
    }

    /** AC5（R2）：仅文字、无图（图片选填）→ 202 受理并落库。 */
    @Test
    void submitTextOnlyNoImagesAccepted() throws Exception {
        User u = newUser();
        mvc.perform(post("/api/v1/triage")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new TriageSubmitRequest("只是有点没精神，没拍照", List.of(), null))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.triageId").isNumber());
    }

    /** AC5（R2）：文字空白（即便有图）→ @NotBlank 拦截 422（文字仍必填）。 */
    @Test
    void submitBlankSymptomTextIs422() throws Exception {
        User u = newUser();
        mvc.perform(post("/api/v1/triage")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new TriageSubmitRequest("   ", List.of("priv/k1.jpg"), null))))
                .andExpect(status().isUnprocessableEntity());
    }

    /** 缺 token → 401（端点需 USER JWT）。 */
    @Test
    void submitWithoutJwtIs401() throws Exception {
        mvc.perform(post("/api/v1/triage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new TriageSubmitRequest("咳嗽", null, null))))
                .andExpect(status().isUnauthorized());
    }

    /** 非法 body（图片超过 3 张）→ Bean 校验失败 422。 */
    @Test
    void submitWithTooManyImagesIs422() throws Exception {
        User u = newUser();
        mvc.perform(post("/api/v1/triage")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new TriageSubmitRequest(
                                "咳嗽", List.of("k1", "k2", "k3", "k4"), null))))
                .andExpect(status().isUnprocessableEntity());
    }

    /** 非法 body（症状超过 2000 字）→ 422。 */
    @Test
    void submitWithOversizeSymptomIs422() throws Exception {
        User u = newUser();
        String tooLong = "咳".repeat(2001);
        mvc.perform(post("/api/v1/triage")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new TriageSubmitRequest(tooLong, null, null))))
                .andExpect(status().isUnprocessableEntity());
    }

    /** 写端点限流：单 user 连发越过阈值（10/分钟）→ 第 11 次 429。限流 key 含 userId，独立 actor 不串扰。 */
    @Test
    void submitRateLimitedAfterThreshold() throws Exception {
        User u = newUser();
        TriageSubmitRequest req = new TriageSubmitRequest("咳嗽", null, null);
        // 阈值 SUBMIT_LIMIT=10：前 10 次受理（202），第 11 次越界 → 429。
        for (int i = 0; i < 10; i++) {
            submit(u.getId(), req); // 压计数，受理状态不在此断言
        }
        mvc.perform(post("/api/v1/triage")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isTooManyRequests());
    }

    // ---------- 取结果 ----------

    /** GET 自己的 triage → 200 + 状态字段。 */
    @Test
    void getOwnTriageReturns200() throws Exception {
        User u = newUser();
        long id = triageIdOf(submit(u.getId(), new TriageSubmitRequest("咳嗽", null, null)));

        mvc.perform(get("/api/v1/triage/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());
    }

    /** GET 他人的 triageId → 403（service 越权与不存在统一 403 防枚举）。 */
    @Test
    void getOthersTriageIsForbidden() throws Exception {
        User owner = newUser();
        User intruder = newUser();
        long id = triageIdOf(submit(owner.getId(), new TriageSubmitRequest("咳嗽", null, null)));

        mvc.perform(get("/api/v1/triage/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(intruder.getId())))
                .andExpect(status().isForbidden());
    }

    /** GET 不存在的 triageId → 403（同上，与他人任务不可区分，防枚举）。 */
    @Test
    void getMissingTriageIsForbidden() throws Exception {
        User u = newUser();
        mvc.perform(get("/api/v1/triage/{id}", 999_999_999L)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isForbidden());
    }

    /** GET 缺 token → 401。 */
    @Test
    void getWithoutJwtIs401() throws Exception {
        mvc.perform(get("/api/v1/triage/{id}", 1L))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 安全攸关：后置安全规则层强制升红 ----------

    /**
     * 高危症状（误食巧克力）经后置安全规则层强制升红 → DONE + danger_level=RED。
     *
     * <p>Gemini stub 固定回 GREEN；RED 完全由 {@link com.tailtopia.triage.service.SafetyRuleLayer} 后置裁决，
     * 证明「红色不被模型假阴性绕过」。@Async 异步不易断言，故同步驱动 {@link TriageProcessor#process} 后 GET。
     */
    @Test
    void highRiskSymptomForcedToRed() throws Exception {
        User u = newUser();
        long id = triageIdOf(submit(u.getId(),
                new TriageSubmitRequest("我家狗误食巧克力了，怎么办", null, null)));

        // 同步处理（绕过 @Async 时序）：领取 → stub GREEN → 安全层强制升红 → DONE。
        triageProcessor.process(id);

        mvc.perform(get("/api/v1/triage/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.dangerLevel").value("RED"));

        TriageTask done = triageTasks.findById(id).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(done.getStatus()).isEqualTo(TriageStatus.DONE);
        org.assertj.core.api.Assertions.assertThat(done.getDangerLevel()).isEqualTo(DangerLevel.RED);
    }

    /**
     * 无高危信号的普通症状经处理 → DONE，保留模型 GREEN（安全层只升不降、不误升）。
     * 验证状态机正常流转 PENDING → DONE。
     */
    @Test
    void benignSymptomStaysGreen() throws Exception {
        User u = newUser();
        long id = triageIdOf(submit(u.getId(),
                new TriageSubmitRequest("最近偶尔打喷嚏，精神食欲都正常", null, null)));

        triageProcessor.process(id);

        mvc.perform(get("/api/v1/triage/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.dangerLevel").value("GREEN"));
    }
}
