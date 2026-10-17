package com.tailtopia.shared.media.web;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.VipLevelService;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * L1（需 Postgres + Redis）：专题线 video Story 1.2 —— {@code POST /api/v1/media/video-upload-policy}。
 *
 * <p>策略签名经 {@link MockitoBean} 桩出（无真实凭证）；等级、限流计数、序列化走真实链路。
 * 每个用例用新用户，Redis 计数不串号。
 */
@TestPropertySource(properties = {
        "media.oss.public-bucket=petgo-public-test",
        "media.oss.endpoint=https://oss-ap-southeast-5.aliyuncs.com",
        "media.video.daily-policy-limit=50"
})
class VideoUploadPolicyEndpointTest extends ApiIntegrationTest {

    @MockitoBean
    private AliyunOssClient ossClient;

    @Autowired
    private VipLevelService vipLevelService;

    @BeforeEach
    void stubSigner() {
        Mockito.when(ossClient.signPostPolicy(Mockito.any(), Mockito.any()))
                .thenReturn(new AliyunOssClient.PostPolicySignature("cG9saWN5", "sig==", "AKID"));
    }

    private ResultActions request(long userId) throws Exception {
        return mvc.perform(post("/api/v1/media/video-upload-policy")
                .header(HttpHeaders.AUTHORIZATION, userBearer(userId)));
    }

    @Test
    void level1Returns403() throws Exception {
        User u = newUser();
        request(u.getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type", endsWith("video-permission-denied")));
        Mockito.verifyNoInteractions(ossClient);
    }

    @Test
    void level10ReturnsHostKeyAndFields() throws Exception {
        User u = newUser();
        vipLevelService.setLevel(u.getId(), 10);

        request(u.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host", is("https://petgo-public-test.oss-ap-southeast-5.aliyuncs.com")))
                .andExpect(jsonPath("$.key", matchesPattern("video/" + u.getId() + "/[A-Za-z0-9_-]{22}\\.mp4")))
                .andExpect(jsonPath("$.fields.key", matchesPattern("video/" + u.getId() + "/.+\\.mp4")))
                .andExpect(jsonPath("$.fields.policy", is("cG9saWN5")))
                .andExpect(jsonPath("$.fields.OSSAccessKeyId", is("AKID")))
                .andExpect(jsonPath("$.fields.signature", is("sig==")))
                .andExpect(jsonPath("$.fields.success_action_status", is("200")))
                .andExpect(jsonPath("$.fields['Content-Type']", is("video/mp4")))
                .andExpect(jsonPath("$.fields['x-oss-object-acl']", is("private")))
                .andExpect(jsonPath("$.fields['x-oss-forbid-overwrite']", is("true")));
    }

    @Test
    void fiftyFirstRequestOfTheDayReturns429() throws Exception {
        User u = newUser();
        vipLevelService.setLevel(u.getId(), 10);
        for (int i = 0; i < 50; i++) {
            request(u.getId()).andExpect(status().isOk());
        }
        request(u.getId())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type", endsWith("video-upload-quota-exceeded")));
    }

    @Test
    void deniedRequestsDoNotConsumeQuota() throws Exception {
        User u = newUser();
        for (int i = 0; i < 60; i++) {
            request(u.getId()).andExpect(status().isForbidden());
        }
        vipLevelService.setLevel(u.getId(), 10);
        request(u.getId()).andExpect(status().isOk());
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mvc.perform(post("/api/v1/media/video-upload-policy")).andExpect(status().isUnauthorized());
    }
}
