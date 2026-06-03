package com.petgo.notify.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgo.auth.domain.User;
import com.petgo.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * {@link NotifyTestController} 端点集成测试（{@code POST /api/v1/notify/_test-push}，仅 dev profile）。
 *
 * <p>测试基类已 {@code @ActiveProfiles("dev")}，故该端点在场。它经统一推送出口写一行 VET_REPLY 通知
 * 并返回 {@link com.petgo.notify.dto.PushPayload}（type/deepLinkToken/title/body）。需 USER JWT
 * （落 {@code anyRequest().authenticated()}），缺 token → 401。
 */
class NotifyTestControllerEndpointTest extends ApiIntegrationTest {

    /** 正常触发：200 + 返回 PushPayload（type=VET_REPLY、非空 token/title/body，token 不外泄顺序 id）。 */
    @Test
    void testPush_returnsPayload() throws Exception {
        User me = newUser();

        mvc.perform(post("/api/v1/notify/_test-push")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("VET_REPLY"))
                .andExpect(jsonPath("$.deepLinkToken").isNotEmpty())
                .andExpect(jsonPath("$.title").value("测试推送"))
                .andExpect(jsonPath("$.body").value("这是一条测试通知"));
    }

    /** 触发后该用户通知中心可见这条 + 未读数为 1（贯穿写库 + 角标自增）。 */
    @Test
    void testPush_appearsInNotificationCenter() throws Exception {
        User me = newUser();

        String pushBody = mvc.perform(post("/api/v1/notify/_test-push")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(pushBody).get("deepLinkToken").asString();

        mvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].deepLinkToken").value(token))
                .andExpect(jsonPath("$.items[0].read").value(false));

        mvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    /** 缺 token → 401（端点落 anyRequest().authenticated()）。 */
    @Test
    void testPush_missingToken_is401() throws Exception {
        mvc.perform(post("/api/v1/notify/_test-push"))
                .andExpect(status().isUnauthorized());
    }
}
