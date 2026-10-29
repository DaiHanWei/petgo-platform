package com.tailtopia.shared.media.web;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link MediaController} 集成测试（L1，真 Spring + 安全链 + 限流 + 序列化）。
 *
 * <p>{@code POST /api/v1/media/upload-url}：需 JWT。dev 默认桶为空、且无真实阿里云凭证，故用
 * {@link MockitoBean} 把 {@link AliyunOssClient#presignedPutUrl} 桩出确定 URL，并经 {@link TestPropertySource}
 * 配上桶名/CDN，跑通完整 HTTP→鉴权→限流→{@link com.tailtopia.shared.media.PresignedUploadService} 签发→序列化的
 * 200 路径（objectKey 收窄在 public/&lt;userId&gt;/，公开域含 public-read 头与 publicUrl）。
 */
@TestPropertySource(properties = {
        "media.oss.public-bucket=petgo-public-test",
        "media.oss.private-bucket=petgo-private-test",
        "media.oss.cdn-base-url=https://cdn.petgo.test"
})
class MediaControllerEndpointTest extends ApiIntegrationTest {

    /** 把真实 OSS 预签名调用边界换成确定性桩（无真实凭证下闭环签发逻辑）。 */
    @MockitoBean
    private AliyunOssClient ossClient;

    @org.springframework.beans.factory.annotation.Autowired
    private com.tailtopia.auth.service.VipLevelService vipLevelService;

    @Test
    void userIssuesPublicUploadUrlReturns200WithScopedFields() throws Exception {
        Mockito.when(ossClient.presignedPutUrl(
                        Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                        Mockito.anyLong(), Mockito.eq("public-read")))
                .thenReturn("https://signed.example/put");

        User actor = newUser();

        mvc.perform(post("/api/v1/media/upload-url")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"PUBLIC\",\"contentType\":\"image/jpeg\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl", is("https://signed.example/put")))
                .andExpect(jsonPath("$.method", is("PUT")))
                // objectKey 收窄到 public/<userId>/，不可枚举随机段 + .jpg。
                .andExpect(jsonPath("$.objectKey",
                        matchesPattern("public/" + actor.getId() + "/[A-Za-z0-9_-]+\\.jpg")))
                .andExpect(jsonPath("$.headers['Content-Type']", is("image/jpeg")))
                .andExpect(jsonPath("$.headers['x-oss-object-acl']", is("public-read")))
                // 专题线 video Story 1.2：禁止覆盖头必须回给客户端（旧版 App 原样回带 headers）。
                .andExpect(jsonPath("$.headers['x-oss-forbid-overwrite']", is("true")))
                .andExpect(jsonPath("$.publicUrl",
                        matchesPattern("https://cdn.petgo.test/public/" + actor.getId() + "/.+\\.jpg")));
    }

    @Test
    void privateScopeOmitsPublicUrlAndAclHeader() throws Exception {
        Mockito.when(ossClient.presignedPutUrl(
                        Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                        Mockito.anyLong(), Mockito.isNull()))
                .thenReturn("https://signed.example/put-private");

        User actor = newUser();

        mvc.perform(post("/api/v1/media/upload-url")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"PRIVATE\",\"contentType\":\"image/jpeg\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectKey",
                        matchesPattern("private/" + actor.getId() + "/.+\\.jpg")))
                // 私密域绝不打 public-read ACL、绝不返回公开 URL（NON_NULL：字段省略）。
                .andExpect(jsonPath("$.headers['x-oss-object-acl']").doesNotExist())
                .andExpect(jsonPath("$.headers['x-oss-forbid-overwrite']", is("true")))
                .andExpect(jsonPath("$.publicUrl").doesNotExist());
    }

    @Test
    void missingScopeReturns422() throws Exception {
        User actor = newUser();

        mvc.perform(post("/api/v1/media/upload-url")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/jpeg\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mvc.perform(post("/api/v1/media/upload-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"PUBLIC\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- 专题线 video Story 1.2：类型白名单 + 视频封面用途 ----

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"scope\":\"PUBLIC\"}",
            "{\"scope\":\"PUBLIC\",\"contentType\":\"\"}",
            "{\"scope\":\"PUBLIC\",\"contentType\":\"video/mp4\"}",
            "{\"scope\":\"PRIVATE\",\"contentType\":\"text/html\"}",
            "{\"scope\":\"PRIVATE\",\"contentType\":\"application/octet-stream\"}"})
    void nonImageContentTypeReturns422WithDedicatedType(String body) throws Exception {
        User actor = newUser();

        mvc.perform(post("/api/v1/media/upload-url")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type", endsWith("upload-content-type-not-allowed")));
        Mockito.verifyNoInteractions(ossClient);
    }

    @Test
    void videoCoverBelowLevel10Returns403() throws Exception {
        User actor = newUser();

        mvc.perform(post("/api/v1/media/upload-url")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"PUBLIC\",\"contentType\":\"image/jpeg\",\"purpose\":\"VIDEO_COVER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type", endsWith("video-permission-denied")));
    }

    @Test
    void videoCoverAtLevel10SignsPrivateUnderPublicPrefix() throws Exception {
        Mockito.when(ossClient.presignedPutUrl(
                        Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                        Mockito.anyLong(), Mockito.eq("private")))
                .thenReturn("https://signed.example/put-cover");
        User actor = newUser();
        vipLevelService.setLevel(actor.getId(), 10);

        mvc.perform(post("/api/v1/media/upload-url")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"PUBLIC\",\"contentType\":\"image/jpeg\",\"purpose\":\"VIDEO_COVER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl", is("https://signed.example/put-cover")))
                .andExpect(jsonPath("$.objectKey",
                        matchesPattern("public/" + actor.getId() + "/[A-Za-z0-9_-]+\\.jpg")))
                .andExpect(jsonPath("$.headers['x-oss-object-acl']", is("private")))
                .andExpect(jsonPath("$.headers['x-oss-forbid-overwrite']", is("true")));
    }
}
