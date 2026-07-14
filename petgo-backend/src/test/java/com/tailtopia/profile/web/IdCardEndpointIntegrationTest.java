package com.tailtopia.profile.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * L1 集成测试：{@code /api/v1/pet-profiles/me/id-card}（Story 6.1，FR-49A）真 HTTP 链路。
 *
 * <p>覆盖：老用户（无 serial）GET → {@code generated=false}；POST 分配号 → {@code generated=true}+号；
 * POST 幂等（二次返同号）；无档案 GET/POST → 404；未登录 → 401。
 */
class IdCardEndpointIntegrationTest extends ApiIntegrationTest {

    private String createBody() {
        return """
                {"name":"旺财","petType":"DOG","breed":"柴犬","intro":"乖巧","birthday":"2022-01-01"}
                """;
    }

    private void createProfile(String token) throws Exception {
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated());
    }

    @Test
    void getIdCardForFreshProfileReportsNotGenerated() throws Exception {
        User owner = newUser();
        String token = userBearer(owner.getId());
        createProfile(token);

        // 新建/老用户档案：serial 为 null → generated=false，serialId 省略（NON_NULL）。
        mvc.perform(get("/api/v1/pet-profiles/me/id-card")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generated").value(false))
                .andExpect(jsonPath("$.serialId").doesNotExist())
                .andExpect(jsonPath("$.name").value("旺财"))
                .andExpect(jsonPath("$.petType").value("DOG"));
    }

    @Test
    void postGeneratesSerialThenGetReportsGenerated() throws Exception {
        User owner = newUser();
        String token = userBearer(owner.getId());
        createProfile(token);

        // 生成：分配号 → generated=true + serialId（≥1）。
        mvc.perform(post("/api/v1/pet-profiles/me/id-card")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generated").value(true))
                .andExpect(jsonPath("$.serialId").isNumber());

        // GET 反映已生成态。
        mvc.perform(get("/api/v1/pet-profiles/me/id-card")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generated").value(true))
                .andExpect(jsonPath("$.serialId").isNumber());
    }

    @Test
    void postIsIdempotentReturnsSameSerial() throws Exception {
        User owner = newUser();
        String token = userBearer(owner.getId());
        createProfile(token);

        String first = mvc.perform(post("/api/v1/pet-profiles/me/id-card")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long serial1 = json.readTree(first).get("serialId").asLong();

        // 幂等：二次生成返同号，不换号。
        mvc.perform(post("/api/v1/pet-profiles/me/id-card")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialId").value(serial1));
    }

    @Test
    void getIdCardWithoutProfileIs404() throws Exception {
        User owner = newUser();
        mvc.perform(get("/api/v1/pet-profiles/me/id-card")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void postIdCardWithoutProfileIs404() throws Exception {
        User owner = newUser();
        mvc.perform(post("/api/v1/pet-profiles/me/id-card")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void idCardWithoutTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/pet-profiles/me/id-card"))
                .andExpect(status().isUnauthorized());
    }
}
