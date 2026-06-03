package com.petgo.shared.media.web;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgo.auth.domain.User;
import com.petgo.shared.media.AliyunStsClient;
import com.petgo.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link MediaController} 集成测试（L1，真 Spring + 安全链 + 限流 + 序列化）。
 *
 * <p>{@code POST /api/v1/media/sts-credentials}：需 JWT。dev 默认桶为空、且无真实阿里云凭证，
 * 故用 {@link MockitoBean} 替换 {@link AliyunStsClient} 桩出确定凭证，并经 {@link TestPropertySource}
 * 配上桶名，跑通完整 HTTP→鉴权→限流→{@code StsService} 收窄→序列化的 200 直传凭证路径。
 *
 * <p>覆盖：USER 取 STS 凭证→200 + 字段（含按 userId 收窄的 uploadDir）；非法 body（缺 scope）→422；缺 token→401。
 */
@TestPropertySource(properties = {
        "media.oss.public-bucket=petgo-public-test",
        "media.oss.private-bucket=petgo-private-test",
        "media.oss.cdn-base-url=https://cdn.petgo.test"
})
class MediaControllerEndpointTest extends ApiIntegrationTest {

    /** 替换真实阿里云 STS 调用边界为确定性桩（无真实凭证下闭环业务逻辑）。 */
    @MockitoBean
    private AliyunStsClient stsClient;

    @Test
    void userIssuesStsCredentialReturns200WithScopedFields() throws Exception {
        Mockito.when(stsClient.assumeRole(Mockito.anyString(), Mockito.anyLong(), Mockito.anyString()))
                .thenReturn(new AliyunStsClient.AssumedCredential(
                        "STS.ak-test", "sk-test", "tok-test", "2026-06-03T00:00:00Z"));

        User actor = newUser();

        mvc.perform(post("/api/v1/media/sts-credentials")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"PUBLIC\",\"contentType\":\"image/jpeg\",\"count\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessKeyId", is("STS.ak-test")))
                .andExpect(jsonPath("$.securityToken", is("tok-test")))
                .andExpect(jsonPath("$.bucket", is("petgo-public-test")))
                .andExpect(jsonPath("$.region", is("ap-southeast-5")))
                // uploadDir 收窄到 public/<userId>/，防越权写他人前缀。
                .andExpect(jsonPath("$.uploadDir", is("public/" + actor.getId() + "/")))
                .andExpect(jsonPath("$.cdnBaseUrl", is("https://cdn.petgo.test")));
    }

    @Test
    void privateScopeOmitsCdnBaseUrl() throws Exception {
        Mockito.when(stsClient.assumeRole(Mockito.anyString(), Mockito.anyLong(), Mockito.anyString()))
                .thenReturn(new AliyunStsClient.AssumedCredential(
                        "STS.ak2", "sk2", "tok2", "2026-06-03T00:00:00Z"));

        User actor = newUser();

        mvc.perform(post("/api/v1/media/sts-credentials")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"PRIVATE\",\"count\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucket", is("petgo-private-test")))
                .andExpect(jsonPath("$.uploadDir", is("private/" + actor.getId() + "/")))
                // 私密桶绝不返回 CDN base（NON_NULL：字段省略）。
                .andExpect(jsonPath("$.cdnBaseUrl").doesNotExist());
    }

    @Test
    void missingScopeReturns422() throws Exception {
        User actor = newUser();

        mvc.perform(post("/api/v1/media/sts-credentials")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/jpeg\",\"count\":1}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mvc.perform(post("/api/v1/media/sts-credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"PUBLIC\",\"count\":1}"))
                .andExpect(status().isUnauthorized());
    }
}
