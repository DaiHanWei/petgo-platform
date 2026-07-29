package com.tailtopia.profile.web;

import static org.assertj.core.api.Assertions.assertThat;
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

    // ---- spec-ktp-pet-idcode-numbering：独立建卡走《宠物身份码护照编码规则》 ----

    /** 建卡请求体（母猫、生日 2024-03-10 = spec I/O 矩阵示例）。 */
    private String newCardBody() {
        return """
                {"name":"Momo","petType":"CAT","breed":"英短","birthday":"2024-03-10","gender":"FEMALE"}
                """;
    }

    private String postCard(String token, String body) throws Exception {
        return mvc.perform(post("/api/v1/pet-profiles/me/id-cards")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void createCardAllocatesRuleBasedNumbers() throws Exception {
        User owner = newUser();
        String body = postCard(userBearer(owner.getId()), newCardBody());

        var node = json.readTree(body);
        // 身份码：TT + 60(日10+母50) + 03 + 24 + 02(猫) + 4位序号；护照号：TT + 02 + P + 年后两位 + 5位序号。
        assertThat(node.get("cardNo").asString()).matches("TT60032402\\d{4}");
        assertThat(node.get("passportNo").asString()).matches("TT02P\\d{7}");
        assertThat(node.get("gender").asString()).isEqualTo("FEMALE");
        assertThat(node.get("serialId").isNumber()).isTrue(); // legacy serial 照旧分配
    }

    @Test
    void createCardSameDaySameSpeciesIncrementsSequence() throws Exception {
        User owner = newUser();
        String token = userBearer(owner.getId());

        String no1 = json.readTree(postCard(token, newCardBody())).get("cardNo").asString();
        String no2 = json.readTree(postCard(token, newCardBody())).get("cardNo").asString();

        // 同一 WIB 日同物种：身份码末四位顺序号 +1（0001→0002 语义）。
        int seq1 = Integer.parseInt(no1.substring(10));
        int seq2 = Integer.parseInt(no2.substring(10));
        assertThat(seq2).isEqualTo(seq1 + 1);
        assertThat(no2.substring(0, 10)).isEqualTo(no1.substring(0, 10));
    }

    @Test
    void createCardWithoutBirthdayIsRejected() throws Exception {
        // birthday @NotNull：项目全局把 Bean 校验失败映射为 422 ProblemDetail（GlobalExceptionHandler）。
        User owner = newUser();
        mvc.perform(post("/api/v1/pet-profiles/me/id-cards")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Momo","petType":"CAT","gender":"FEMALE"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createCardWithInvalidGenderIsRejected() throws Exception {
        // gender @Pattern：与 birthday 等字段校验统一走 Bean 校验 → 422（不再服务层手抛 400）。
        User owner = newUser();
        mvc.perform(post("/api/v1/pet-profiles/me/id-cards")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Momo","petType":"CAT","birthday":"2024-03-10","gender":"OTHER"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createCardWithFutureBirthdayIsRejected() throws Exception {
        // birthday @PastOrPresent：快照冻结不可改，未来日期会永久固化荒谬编号。
        User owner = newUser();
        mvc.perform(post("/api/v1/pet-profiles/me/id-cards")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Momo","petType":"CAT","birthday":"2999-12-31","gender":"FEMALE"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }
}
