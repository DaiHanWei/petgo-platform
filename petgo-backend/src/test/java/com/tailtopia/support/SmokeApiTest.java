package com.petgo.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgo.auth.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** 集成测试骨架冒烟：放行端点、JWT 鉴权端点、缺 token 401（验证 {@link ApiIntegrationTest} 链路通）。 */
class SmokeApiTest extends ApiIntegrationTest {

    @Test
    void publicAppVersionIsOpen() throws Exception {
        mvc.perform(get("/api/v1/app-version"))
                .andExpect(status().isOk());
    }

    @Test
    void meRequiresJwtAndReturnsCurrentUser() throws Exception {
        User u = newUser();
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(u.getId()));
    }

    @Test
    void meWithoutJwtIs401() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized());
    }
}
