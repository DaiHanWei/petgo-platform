package com.tailtopia.profile.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * L1 集成测试：{@code /api/v1/pet-profiles} 真 HTTP 链路（序列化 + Bean 校验 + JWT 鉴权 + ProblemDetail + 落库）。
 *
 * <p>实际端点（4 个映射）：{@code POST /pet-profiles}（创建，201）、{@code GET /pet-profiles/me}（查自己，无则 404）、
 * {@code PATCH /pet-profiles/me}（部分更新）、{@code GET /pet-profiles/me/timeline}（成长时间线）。
 * V1 单账号单宠物：owner 取自 JWT，越权天然不可达——「改不到他人档案」通过「他人 JWT 看到的是自己档案/404」体现。
 */
class ProfileApiControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private PetProfileRepository profiles;

    @Autowired
    private com.tailtopia.content.repository.ContentPostRepository contentPosts;

    private String createBody(String name) {
        return """
                {"name":"%s","petType":"DOG","breed":"柴犬","intro":"乖巧","birthday":"2022-01-01"}
                """.formatted(name);
    }

    // ---------- 创建 ----------

    @Test
    void createProfileReturns201AndPersists() throws Exception {
        User owner = newUser();

        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("旺财")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("旺财"))
                .andExpect(jsonPath("$.petType").value("DOG"))
                .andExpect(jsonPath("$.breed").value("柴犬"))
                .andExpect(jsonPath("$.cardToken").isNotEmpty())
                .andExpect(jsonPath("$.id").isNumber());

        PetProfile saved = profiles.findByOwnerId(owner.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("旺财", saved.getName());
        org.junit.jupiter.api.Assertions.assertEquals(owner.getId(), saved.getOwnerId());
        org.junit.jupiter.api.Assertions.assertEquals(
                com.tailtopia.profile.domain.PetType.DOG, saved.getPetType());
    }

    // ---------- R2/F6 必填校验（决策 F6 + R2/AC3） ----------

    @Test
    void createMissingPetTypeIs422() throws Exception {
        User owner = newUser();
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"旺财","birthday":"2022-01-01"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createInvalidPetTypeIs422() throws Exception {
        User owner = newUser();
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"旺财","petType":"BIRD","birthday":"2022-01-01"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createMissingBirthdayIs422() throws Exception {
        User owner = newUser();
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"旺财","petType":"CAT"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createOptionalFieldsOmittedSucceeds() throws Exception {
        // 选填（头像/品种/介绍）缺省不阻塞：仅必填（类型/名字/生日）即可创建。
        User owner = newUser();
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"旺财","petType":"OTHER","birthday":"2021-05-05"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.petType").value("OTHER"));
    }

    @Test
    void createWithoutTokenIs401() throws Exception {
        mvc.perform(post("/api/v1/pet-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("旺财")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createBlankNameIs422() throws Exception {
        User owner = newUser();
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  "}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createNameTooLongIs422() throws Exception {
        User owner = newUser();
        String longName = "名".repeat(21);
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(longName)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createFutureBirthdayIs422() throws Exception {
        User owner = newUser();
        String future = LocalDate.now().plusDays(1).toString();
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"旺财","birthday":"%s"}
                                """.formatted(future)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createTwiceForSameOwnerIs409() throws Exception {
        User owner = newUser();
        String token = userBearer(owner.getId());

        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("旺财")))
                .andExpect(status().isCreated());

        // 单账号单宠物：再建 → 409。
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("小黑")))
                .andExpect(status().isConflict());
    }

    // ---------- 查询自己的档案 ----------

    @Test
    void getMyProfileReturnsOwnProfile() throws Exception {
        User owner = newUser();
        String token = userBearer(owner.getId());
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("旺财")))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/pet-profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("旺财"));
    }

    @Test
    void getMyProfileWhenNoneIs404() throws Exception {
        User owner = newUser();
        mvc.perform(get("/api/v1/pet-profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void getMyProfileWithoutTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/pet-profiles/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerIsolation_eachUserSeesOnlyOwnProfile() throws Exception {
        User a = newUser();
        User b = newUser();

        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("阿狗")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(b.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("阿猫")))
                .andExpect(status().isCreated());

        // B 永远只能看到/改到自己的档案；owner 取自 JWT，无法触达 A 的档案。
        mvc.perform(get("/api/v1/pet-profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(b.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("阿猫"));
    }

    // ---------- 更新（改名等） ----------

    @Test
    void updateRenamesProfileAndPersists() throws Exception {
        User owner = newUser();
        String token = userBearer(owner.getId());
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("旺财")))
                .andExpect(status().isCreated());

        mvc.perform(patch("/api/v1/pet-profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"大旺","intro":"换名字了"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("大旺"))
                .andExpect(jsonPath("$.intro").value("换名字了"));

        PetProfile saved = profiles.findByOwnerId(owner.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("大旺", saved.getName());
        // cardToken 不变（编辑不重生成）。
        org.junit.jupiter.api.Assertions.assertNotNull(saved.getCardToken());
    }

    @Test
    void updateWhenNoProfileIs404() throws Exception {
        User owner = newUser();
        mvc.perform(patch("/api/v1/pet-profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"大旺"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateBlankNameIs422() throws Exception {
        User owner = newUser();
        String token = userBearer(owner.getId());
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("旺财")))
                .andExpect(status().isCreated());

        // 显式传空白名 → service 抛 validation(422)。
        mvc.perform(patch("/api/v1/pet-profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"   "}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void updateWithoutTokenIs401() throws Exception {
        mvc.perform(patch("/api/v1/pet-profiles/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"大旺"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 时间线 ----------

    @Test
    void timelineReturnsEnvelopeForOwner() throws Exception {
        User owner = newUser();
        String token = userBearer(owner.getId());
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("旺财")))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/pet-profiles/me/timeline")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.hasMore").exists());
    }

    @Test
    void timelineWithoutProfileIs404() throws Exception {
        User owner = newUser();
        mvc.perform(get("/api/v1/pet-profiles/me/timeline")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void timelineWithoutTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/pet-profiles/me/timeline"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- R2 · 日历 / 当天 / 统计（F9 + AC5） ----------

    /** 直接造一条属作者的成长日历快乐时刻（含 event_date）。 */
    private void seedGrowth(long ownerId, String createdIso, String eventDate, String img) {
        com.tailtopia.content.domain.ContentPost p = com.tailtopia.content.domain.ContentPost.publish(
                ownerId, com.tailtopia.content.domain.ContentType.GROWTH_MOMENT, petIdOf(ownerId),
                "moment", java.util.List.of(img), LocalDate.parse(eventDate));
        contentPosts.save(p);
    }

    private long petIdOf(long ownerId) {
        return profiles.findByOwnerId(ownerId).orElseThrow().getId();
    }

    @Test
    void calendarAggregatesByEventDate() throws Exception {
        User owner = newUser();
        String token = userBearer(owner.getId());
        mvc.perform(post("/api/v1/pet-profiles").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(createBody("旺财")))
                .andExpect(status().isCreated());
        seedGrowth(owner.getId(), "2026-06-02T08:00:00Z", "2026-06-02", "https://cdn/a.jpg");
        seedGrowth(owner.getId(), "2026-06-02T09:00:00Z", "2026-06-02", "https://cdn/b.jpg");
        seedGrowth(owner.getId(), "2026-06-10T09:00:00Z", "2026-06-10", "https://cdn/c.jpg");

        mvc.perform(get("/api/v1/pet-profiles/me/calendar?year=2026&month=6")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(2))
                .andExpect(jsonPath("$.days[0].day").value(2))
                .andExpect(jsonPath("$.days[0].firstImageUrl").value("https://cdn/a.jpg"))
                .andExpect(jsonPath("$.days[0].hasHappyMoment").value(true));
    }

    @Test
    void dayDetailReturnsItemsForDate() throws Exception {
        User owner = newUser();
        String token = userBearer(owner.getId());
        mvc.perform(post("/api/v1/pet-profiles").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(createBody("旺财")))
                .andExpect(status().isCreated());
        seedGrowth(owner.getId(), "2026-06-02T08:00:00Z", "2026-06-02", "https://cdn/a.jpg");

        mvc.perform(get("/api/v1/pet-profiles/me/day?date=2026-06-02")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-06-02"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("HAPPY_MOMENT"));
    }

    @Test
    void archiveStatsCountsAndMilestoneTotal() throws Exception {
        User owner = newUser();
        String token = userBearer(owner.getId());
        mvc.perform(post("/api/v1/pet-profiles").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(createBody("旺财"))) // DOG
                .andExpect(status().isCreated());
        seedGrowth(owner.getId(), "2026-06-02T08:00:00Z", "2026-06-02", "https://cdn/a.jpg");
        seedGrowth(owner.getId(), "2026-06-03T08:00:00Z", "2026-06-03", "https://cdn/b.jpg");

        mvc.perform(get("/api/v1/pet-profiles/me/archive-stats")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.happyMomentCount").value(2))
                .andExpect(jsonPath("$.consultCount").value(0))
                // 8-3 自动完成：2 条成长瞬间跨过 onGrowthMomentCount>=1 阈值，完成「首条成长记录」里程碑
                .andExpect(jsonPath("$.milestoneCompleted").value(1))
                .andExpect(jsonPath("$.milestoneTotal").value(30)); // DOG = 30
    }

    @Test
    void calendarWithoutProfileIs404() throws Exception {
        User owner = newUser();
        mvc.perform(get("/api/v1/pet-profiles/me/calendar?year=2026&month=6")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isNotFound());
    }
}
