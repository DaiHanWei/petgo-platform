package com.tailtopia.auth.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.PetStatus;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentLike;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * {@link MeController} 端点集成测试：{@code GET /api/v1/me}、{@code PATCH /api/v1/me}。
 *
 * <p>实测端点为 <b>PATCH</b>（非任务描述里的 PUT），仅作用于 JWT {@code sub} 本人——
 * 故重点覆盖：取/改本人聚合视图、Bean 校验（昵称 ≤20）、service 枚举校验（petStatus∈A/B/C）、
 * 缺 token 401、以及防越权（带 A 的 token 改不到 B）。
 */
class MeControllerEndpointTest extends ApiIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private ContentPostRepository posts;

    @org.springframework.beans.factory.annotation.Autowired
    private ContentLikeRepository likes;

    /**
     * V1.3.4 follow Story 1.2 · AC5（D-5 / T-5）：/me 带 token、两关注计数；帖子数计公开 + 私密 + 待审、
     * 不计删除帖；获赞含私密帖上的赞；PATCH 响应同样带这五个字段。
     */
    @Test
    void getAndPatchMe_carryOwnPostStatsAndFollowFields() throws Exception {
        User u = newUser(PetStatus.HAS_PET);
        User fan = newUser();
        posts.save(ContentPost.publish(u.getId(), ContentType.DAILY, null, "公开", java.util.List.of()));
        ContentPost priv = ContentPost.publish(u.getId(), ContentType.DAILY, null, "私密", java.util.List.of());
        priv.setVisibility(ContentVisibility.PRIVATE);
        priv = posts.save(priv);
        posts.save(ContentPost.pendingReview(u.getId(), ContentType.DAILY, null, "待审", java.util.List.of(), null));
        ContentPost gone = ContentPost.publish(u.getId(), ContentType.DAILY, null, "已删", java.util.List.of());
        gone.softDelete();
        posts.save(gone);
        likes.save(ContentLike.of(priv.getId(), fan.getId()));

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userToken").value(u.getPublicToken()))
                .andExpect(jsonPath("$.followingCount").value(0))
                .andExpect(jsonPath("$.followerCount").value(0))
                .andExpect(jsonPath("$.postCount").value(3))
                .andExpect(jsonPath("$.likeCount").value(1));

        mvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"四格\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userToken").value(u.getPublicToken()))
                .andExpect(jsonPath("$.postCount").value(3))
                .andExpect(jsonPath("$.likeCount").value(1))
                .andExpect(jsonPath("$.followingCount").exists())
                .andExpect(jsonPath("$.followerCount").exists());
    }

    /** GET 正常路径：带本人 token → 200 + 回显本人资料字段。 */
    @Test
    void getMe_returnsCurrentUser() throws Exception {
        User u = newUser(PetStatus.HAS_PET);
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(u.getId()))
                .andExpect(jsonPath("$.nickname").value(u.getNickname()))
                .andExpect(jsonPath("$.petStatus").value("HAS_PET"))
                .andExpect(jsonPath("$.onboardingCompleted").value(true));
    }

    /** V1.4.0 ① Story 1.4 · AC5：GET 与 PATCH /me 都带成长值摘要（L1）。 */
    @Test
    void getAndPatchMe_carryGrowthBrief() throws Exception {
        User u = newUser(PetStatus.HAS_PET);
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.growth.level").value(1))
                .andExpect(jsonPath("$.growth.tier").value("NEWBIE"))
                .andExpect(jsonPath("$.growth.frame").value("NONE"))
                .andExpect(jsonPath("$.growth.lifetimeTotal").value(0))
                .andExpect(jsonPath("$.growth.nextThreshold").value(70));
        Map<String, Object> body = new HashMap<>();
        body.put("nickname", "胶囊");
        mvc.perform(patch("/api/v1/me").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.growth.level").value(1));
    }

    /** GET 鉴权：缺 token → 401。 */
    @Test
    void getMe_withoutToken_is401() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    /** PATCH 正常路径：改昵称 + 宠物状态 → 200 回显，并断言真落库（repository 复查）。 */
    @Test
    void patchMe_updatesNicknameAndStatus_persisted() throws Exception {
        User u = newUser(PetStatus.HAS_PET);
        Map<String, Object> body = new HashMap<>();
        body.put("nickname", "新昵称");
        body.put("petStatus", "PLANNING");

        mvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("新昵称"))
                .andExpect(jsonPath("$.petStatus").value("PLANNING"));

        User reloaded = users.findById(u.getId()).orElseThrow();
        Assertions.assertEquals("新昵称", reloaded.getNickname());
        Assertions.assertEquals(PetStatus.PLANNING, reloaded.getPetStatus());
    }

    /** PATCH 校验：昵称超过 20 字（@Size(max=20)）→ 422，且原值不被改动。 */
    @Test
    void patchMe_nicknameTooLong_is422_andNotPersisted() throws Exception {
        User u = newUser(PetStatus.HAS_PET);
        String original = u.getNickname();
        String tooLong = "x".repeat(21);

        mvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nickname", tooLong))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));

        Assertions.assertEquals(original, users.findById(u.getId()).orElseThrow().getNickname());
    }

    /** PATCH 校验：petStatus 非法枚举（service 校验）→ 422。 */
    @Test
    void patchMe_invalidPetStatus_is422() throws Exception {
        User u = newUser(PetStatus.HAS_PET);
        mvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("petStatus", "Z"))))
                .andExpect(status().isUnprocessableEntity());
    }

    /** PATCH 校验：昵称仅空白（service trim 后为空）→ 422。 */
    @Test
    void patchMe_blankNickname_is422() throws Exception {
        User u = newUser(PetStatus.HAS_PET);
        mvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nickname", "   "))))
                .andExpect(status().isUnprocessableEntity());
    }

    /** PATCH 鉴权：缺 token → 401。 */
    @Test
    void patchMe_withoutToken_is401() throws Exception {
        mvc.perform(patch("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nickname", "x"))))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 防越权：用户 A 的 token 调 PATCH /me 只改 A 自己，绝不触碰用户 B——
     * 端点不接受任意 userId，作用对象恒为 JWT sub。断言 B 的昵称/状态原封不动。
     */
    @Test
    void patchMe_onlyAffectsTokenSubject_notOtherUser() throws Exception {
        User a = newUser(PetStatus.HAS_PET);
        User b = newUser(PetStatus.ENTHUSIAST);
        String bNickname = b.getNickname();

        mvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nickname", "A被改了", "petStatus", "PLANNING"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(a.getId()))
                .andExpect(jsonPath("$.nickname").value("A被改了"));

        User reloadedB = users.findById(b.getId()).orElseThrow();
        Assertions.assertEquals(bNickname, reloadedB.getNickname(), "B 的昵称不应被 A 的请求改动");
        Assertions.assertEquals(PetStatus.ENTHUSIAST, reloadedB.getPetStatus(), "B 的状态不应被 A 的请求改动");
    }

    /**
     * V1.1.6 Story 7.2（AC6）：`/me` 必须下发**注册时间** ——
     * FR-70 手机号软引导的时机是「用户第 3 天打开 App」，客户端**只能**靠它算。
     *
     * <p>🔴 不得改用「首次启动时本地记一个日期」绕开：那样**存量用户**（注册已久）会被当成
     * 新人再等两天，重装 App 更是重新计时。注册时间只有服务端知道。
     *
     * <p>⚠️ `users.created_at` 列早就有，本条只是把它下发，**不涉及迁移**。
     */
    @Test
    void meReturnsCreatedAtForPhonePromptTiming() throws Exception {
        User u = newUser();

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAt").exists());
    }
}
