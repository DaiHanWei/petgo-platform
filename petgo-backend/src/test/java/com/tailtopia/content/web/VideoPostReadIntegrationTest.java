package com.tailtopia.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.usermgmt.service.VideoTrustedPublisherAdminService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.VipLevelService;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.media.SignedUrlService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
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
 * L1（需 Postgres + Redis）：专题线 video Story 2.1 —— 帖子接口下发 {@code mediaType} / {@code video}，
 * 作者看未公开的视频帖拿签名地址。
 *
 * <p>OSS 用 {@link MockitoSpyBean}：桩 {@code headObject / isObjectPrivate}；{@code hasCredentials=false}
 * 让发布时不做即时对账（applied 保持 PRIVATE），公开态用 SQL 直接改 applied。签名服务桩成可识别的 URL。
 */
@TestPropertySource(properties = {
        "media.oss.public-bucket=petgo-public-test",
        "media.oss.cdn-base-url=https://cdn.petgo.test"
})
class VideoPostReadIntegrationTest extends ApiIntegrationTest {

    @MockitoSpyBean
    private AliyunOssClient oss;
    @MockitoSpyBean
    private SignedUrlService signer;

    @Autowired
    private VipLevelService vipLevels;
    @Autowired
    private VideoTrustedPublisherAdminService trusted;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void stubOss() {
        Mockito.doReturn(false).when(oss).hasCredentials();
        Mockito.doReturn(Optional.of(new AliyunOssClient.ObjectHead("video/mp4", 1000L)))
                .when(oss).headObject(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        Mockito.doReturn(Optional.of(true))
                .when(oss).isObjectPrivate(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        Mockito.doAnswer(inv -> "https://signed.test/" + inv.getArgument(1) + "?Signature=s")
                .when(signer).sign(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                        ArgumentMatchers.any(Duration.class));
    }

    private User videoUser(boolean inWhitelist) {
        User u = newUser();
        vipLevels.setLevel(u.getId(), 10);
        if (inWhitelist) {
            trusted.add(u.getId(), adminActorId());
        }
        return u;
    }

    private long publishVideo(User u) throws Exception {
        ObjectNode b = json.createObjectNode();
        b.put("type", "KNOWLEDGE");
        b.put("text", "kucing menginjak");
        b.put("mediaType", "VIDEO");
        b.putArray("imageUrls").add("https://cdn.petgo.test/public/" + u.getId() + "/cover.jpg");
        ObjectNode v = b.putObject("video");
        v.put("objectKey", "video/" + u.getId() + "/abcdefghijklmnopqrstuv.mp4");
        v.put("durationMs", 48_000);
        v.put("width", 720);
        v.put("height", 1280);
        mvc.perform(post("/api/v1/content-posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(b)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaType").value("VIDEO"));
        return jdbc.queryForObject("SELECT max(id) FROM content_posts WHERE author_id = ?", Long.class, u.getId());
    }

    private JsonNode getJson(String path, Long viewer) throws Exception {
        var req = get(path);
        if (viewer != null) {
            req = req.header(HttpHeaders.AUTHORIZATION, userBearer(viewer));
        }
        String body = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static JsonNode itemOf(JsonNode page, long id) {
        for (JsonNode it : page.get("items")) {
            if (it.get("id").asLong() == id) {
                return it;
            }
        }
        return null;
    }

    @Test
    void publicVideoPostCarriesMediaTypeAndOriginUrlEverywhere() throws Exception {
        User author = videoUser(true);
        long id = publishVideo(author);
        jdbc.update("UPDATE content_posts SET media_acl_applied='PUBLIC' WHERE id=?", id);
        User viewer = newUser();
        String videoUrl = "https://cdn.petgo.test/video/" + author.getId() + "/abcdefghijklmnopqrstuv.mp4";
        String cover = "https://cdn.petgo.test/public/" + author.getId() + "/cover.jpg";

        JsonNode detail = getJson("/api/v1/content-posts/" + id, viewer.getId());
        assertThat(detail.get("mediaType").asText()).isEqualTo("VIDEO");
        assertThat(detail.get("video").get("url").asText()).isEqualTo(videoUrl);
        assertThat(detail.get("video").get("durationMs").asInt()).isEqualTo(48_000);
        assertThat(detail.get("imageUrls").get(0).asText()).isEqualTo(cover);

        JsonNode mine = itemOf(getJson("/api/v1/me/posts", author.getId()), id);
        assertThat(mine).isNotNull();
        assertThat(mine.get("mediaType").asText()).isEqualTo("VIDEO");
        assertThat(mine.get("firstImageUrl").asText()).isEqualTo(cover);
        assertThat(mine.get("video").get("url").asText()).isEqualTo(videoUrl);

        String token = json.readTree(mvc.perform(post("/api/v1/content-posts/" + id + "/share-link")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(author.getId())))
                .andReturn().getResponse().getContentAsString()).get("shareToken").asText();
        JsonNode shared = getJson("/api/v1/public/shared-posts/" + token, null);
        assertThat(shared.get("mediaType").asText()).isEqualTo("VIDEO");
        assertThat(shared.get("video").get("url").asText()).isEqualTo(videoUrl);
        assertThat(shared.has("id")).isFalse();
    }

    @Test
    void authorSeesUnderReviewVideoSignedAndOthersGet404() throws Exception {
        User author = videoUser(false); // 名单外 → UNDER_REVIEW、desired=applied=PRIVATE
        long id = publishVideo(author);

        JsonNode detail = getJson("/api/v1/content-posts/" + id, author.getId());
        assertThat(detail.get("video").get("url").asText()).startsWith("https://signed.test/video/");
        assertThat(detail.get("imageUrls").get(0).asText()).startsWith("https://signed.test/public/");

        JsonNode mine = itemOf(getJson("/api/v1/me/posts", author.getId()), id);
        assertThat(mine.get("firstImageUrl").asText()).startsWith("https://signed.test/");
        assertThat(mine.get("video").get("url").asText()).contains("Signature=");

        mvc.perform(get("/api/v1/content-posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(newUser().getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void imagePostIsUnchangedAndHasNoVideo() throws Exception {
        User u = newUser();
        ObjectNode img = json.createObjectNode();
        img.put("type", "DAILY");
        img.put("text", "halo foto");
        mvc.perform(post("/api/v1/content-posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(img)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaType").value("IMAGE"));
        long id = jdbc.queryForObject("SELECT max(id) FROM content_posts WHERE author_id = ?", Long.class, u.getId());
        JsonNode detail = getJson("/api/v1/content-posts/" + id, u.getId());
        assertThat(detail.get("mediaType").asText()).isEqualTo("IMAGE");
        assertThat(detail.has("video")).isFalse();
    }
}
