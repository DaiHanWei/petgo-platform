package com.tailtopia.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.usermgmt.service.VideoTrustedPublisherAdminService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.VipLevelService;
import com.tailtopia.content.service.MediaAclReconciler;
import com.tailtopia.shared.media.AliyunCdnCacheRefresher;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.media.CdnCacheRefresher;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * L1（需 Postgres + Redis）：专题线 video Story 3.4 AC2 —— 配了 {@code media.video.cdn-base-url}：装配 Aliyun 刷新器，
 * 下发的 {@code video.url} 用 CDN 域名、封面仍走 OSS 源站；对账时刷新的是 CDN 地址。
 * （去掉配置 → Noop + 源站地址：其他视频 L1 都在不配 CDN 的上下文里跑，见 {@code VideoPostReadIntegrationTest}；
 * 空串 → Noop 由 L0 {@code MediaConfigCdnRefresherTest} 覆盖。）
 *
 * <p>真实 CDN 不会被调用：刷新器是 spy，{@code refresh} 打桩为空操作。
 */
@TestPropertySource(properties = {
        "media.oss.public-bucket=petgo-public-test",
        "media.oss.cdn-base-url=https://cdn.petgo.test",
        "media.video.cdn-base-url=https://v.cdn.petgo.test"
})
class VideoCdnSwitchIntegrationTest extends ApiIntegrationTest {

    @MockitoSpyBean
    private AliyunOssClient oss;
    @MockitoSpyBean
    private CdnCacheRefresher cdn;
    @Autowired
    private VipLevelService vipLevels;
    @Autowired
    private VideoTrustedPublisherAdminService trusted;
    @Autowired
    private MediaAclReconciler reconciler;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void stub() {
        Mockito.doReturn(false).when(oss).hasCredentials();
        Mockito.doReturn(Optional.of(new AliyunOssClient.ObjectHead("video/mp4", 1000L)))
                .when(oss).headObject(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        Mockito.doReturn(Optional.of(true))
                .when(oss).isObjectPrivate(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        Mockito.doNothing().when(cdn).refresh(ArgumentMatchers.anyString());
    }

    private long publishTrustedVideo(User u) throws Exception {
        ObjectNode b = json.createObjectNode();
        b.put("type", "KNOWLEDGE");
        b.put("text", "cdn");
        b.put("mediaType", "VIDEO");
        b.putArray("imageUrls").add("https://cdn.petgo.test/public/" + u.getId() + "/cover.jpg");
        ObjectNode v = b.putObject("video");
        v.put("objectKey", "video/" + u.getId() + "/abcdefghijklmnopqrstuv.mp4");
        v.put("durationMs", 48_000);
        v.put("width", 720);
        v.put("height", 1280);
        mvc.perform(post("/api/v1/content-posts").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT max(id) FROM content_posts WHERE author_id = ?", Long.class, u.getId());
    }

    @Test
    void configuredCdnServesVideoFromCdnAndRefreshesOnReconcile() throws Exception {
        assertThat(cdn).isInstanceOf(AliyunCdnCacheRefresher.class);

        User author = newUser();
        vipLevels.setLevel(author.getId(), 10);
        trusted.add(author.getId(), adminActorId());
        long id = publishTrustedVideo(author);

        // 对账（有凭证、OSS 改 ACL 打桩成功）→ 刷新的是 CDN 上的视频地址
        Mockito.doReturn(true).when(oss).hasCredentials();
        Mockito.doReturn(true).when(oss).setObjectAcl(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyBoolean());
        String cdnUrl = "https://v.cdn.petgo.test/video/" + author.getId() + "/abcdefghijklmnopqrstuv.mp4";
        assertThat(reconciler.reconcileOne(id)).isTrue();
        Mockito.verify(cdn).refresh(cdnUrl);
        Mockito.doReturn(false).when(oss).hasCredentials();

        String body = mvc.perform(get("/api/v1/content-posts/" + id)
                        .header(com.tailtopia.shared.web.ClientCapabilities.HEADER, "video")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(newUser().getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode detail = json.readTree(body);
        assertThat(detail.get("video").get("url").asText()).isEqualTo(cdnUrl);
        // 封面 / 图片仍走 OSS 源站（V-38）
        assertThat(detail.get("imageUrls").get(0).asText())
                .isEqualTo("https://cdn.petgo.test/public/" + author.getId() + "/cover.jpg");

        // 列表出口（我的发布；文件已公开 → 作者也拿 CDN 地址）同一个解析器，同样用 CDN 域名
        String mine = mvc.perform(get("/api/v1/me/posts")
                        .header(com.tailtopia.shared.web.ClientCapabilities.HEADER, "video")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(author.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode item = null;
        for (JsonNode it : json.readTree(mine).get("items")) {
            if (it.get("id").asLong() == id) {
                item = it;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.get("video").get("url").asText()).isEqualTo(cdnUrl);
    }
}
