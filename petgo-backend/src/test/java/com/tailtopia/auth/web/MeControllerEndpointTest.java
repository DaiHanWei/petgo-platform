package com.petgo.auth.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgo.auth.domain.PetStatus;
import com.petgo.auth.domain.User;
import com.petgo.support.ApiIntegrationTest;
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
}
