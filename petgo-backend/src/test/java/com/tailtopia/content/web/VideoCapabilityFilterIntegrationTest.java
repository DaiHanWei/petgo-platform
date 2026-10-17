package com.tailtopia.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.usermgmt.service.VideoTrustedPublisherAdminService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.VipLevelService;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.web.ClientCapabilities;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * L1（需 Postgres + Redis）：专题线 video Story 2.2 —— AD-7 能力过滤矩阵。
 *
 * <p>同一条视频帖，对每个出口分别用「带 / 不带」{@code X-App-Capabilities: video} 请求：
 * 列表出口旧版看不到、新版看得到；单条出口旧版 404、新版 200。
 */
@TestPropertySource(properties = {
        "media.oss.public-bucket=petgo-public-test",
        "media.oss.cdn-base-url=https://cdn.petgo.test"
})
class VideoCapabilityFilterIntegrationTest extends ApiIntegrationTest {

    @MockitoSpyBean
    private AliyunOssClient oss;
    @Autowired
    private VipLevelService vipLevels;
    @Autowired
    private VideoTrustedPublisherAdminService trusted;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void stubOss() {
        Mockito.doReturn(false).when(oss).hasCredentials(); // 不即时对账
        Mockito.doReturn(Optional.of(new AliyunOssClient.ObjectHead("video/mp4", 1000L)))
                .when(oss).headObject(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        Mockito.doReturn(Optional.of(true))
                .when(oss).isObjectPrivate(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

    private record Fixture(User author, User viewer, long videoId, String shareToken, long commentId) {
    }

    private Fixture fixture() throws Exception {
        User author = newUser();
        vipLevels.setLevel(author.getId(), 10);
        trusted.add(author.getId(), adminActorId());
        ObjectNode b = json.createObjectNode();
        b.put("type", "DAILY");
        b.put("text", "video kucing");
        b.put("mediaType", "VIDEO");
        b.putArray("imageUrls").add("https://cdn.petgo.test/public/" + author.getId() + "/cover.jpg");
        ObjectNode v = b.putObject("video");
        v.put("objectKey", "video/" + author.getId() + "/abcdefghijklmnopqrstuv.mp4");
        v.put("durationMs", 5000);
        v.put("width", 720);
        v.put("height", 1280);
        mvc.perform(post("/api/v1/content-posts").header(HttpHeaders.AUTHORIZATION, userBearer(author.getId()))
                        .header(ClientCapabilities.HEADER, "video")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
                .andExpect(status().isCreated());
        long id = jdbc.queryForObject("SELECT max(id) FROM content_posts WHERE author_id = ?", Long.class,
                author.getId());
        jdbc.update("UPDATE content_posts SET media_acl_applied='PUBLIC' WHERE id=?", id);

        User viewer = newUser();
        // 关注作者（关注流）+ 一条评论（回复出口）
        jdbc.update("INSERT INTO user_follows (follower_id, followee_id, created_at) VALUES (?, ?, now())",
                viewer.getId(), author.getId());
        ObjectNode c = json.createObjectNode();
        c.put("body", "lucu");
        mvc.perform(post("/api/v1/content-posts/" + id + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(author.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(c)))
                .andExpect(status().is2xxSuccessful());
        long commentId = jdbc.queryForObject("SELECT max(id) FROM comments WHERE post_id = ?", Long.class, id);
        String token = json.readTree(mvc.perform(post("/api/v1/content-posts/" + id + "/share-link")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(author.getId())))
                .andReturn().getResponse().getContentAsString()).get("shareToken").asText();
        return new Fixture(author, viewer, id, token, commentId);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, Long user, boolean video) {
        if (user != null) {
            req = req.header(HttpHeaders.AUTHORIZATION, userBearer(user));
        }
        return video ? req.header(ClientCapabilities.HEADER, "video") : req;
    }

    /** 翻完整个列表出口（最多 50 页），收集所有 id —— 顺带断言不出现空页 + hasMore=true 的情况。 */
    private List<Long> allIds(String path, Long user, boolean video) throws Exception {
        List<Long> ids = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 50; i++) {
            String url = path + (path.contains("?") ? "&" : "?") + (cursor == null ? "" : "cursor=" + cursor);
            JsonNode page = json.readTree(mvc.perform(as(get(url), user, video)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            JsonNode items = page.get("items");
            if (page.path("hasMore").asBoolean(false)) {
                assertThat(items.size()).as("hasMore=true 的页不能是空页：" + path).isPositive();
            }
            items.forEach(it -> ids.add(it.get("id").asLong()));
            if (!page.path("hasMore").asBoolean(false)) {
                break;
            }
            cursor = page.get("nextCursor").asText();
        }
        return ids;
    }

    @Test
    void listExitsHideVideoFromOldClientsOnly() throws Exception {
        Fixture f = fixture();
        long a = f.author().getId();
        for (String path : List.of("/api/v1/content-posts?category=DAILY", "/api/v1/me/following-feed",
                "/api/v1/users/" + a + "/posts")) {
            assertThat(allIds(path, f.viewer().getId(), false)).as("旧版 " + path).doesNotContain(f.videoId());
            assertThat(allIds(path, f.viewer().getId(), true)).as("新版 " + path).contains(f.videoId());
        }
        assertThat(allIds("/api/v1/me/posts", a, false)).doesNotContain(f.videoId());
        assertThat(allIds("/api/v1/me/posts", a, true)).contains(f.videoId());
        // 推荐流（ALL）：作者本人首屏「刚发的帖」插入同样按能力过滤。
        assertThat(allIds("/api/v1/content-posts?category=ALL", a, false)).doesNotContain(f.videoId());
        assertThat(allIds("/api/v1/content-posts?category=ALL", a, true)).contains(f.videoId());
    }

    @Test
    void singleExitsAre404ForOldClientsOnly() throws Exception {
        Fixture f = fixture();
        long u = f.viewer().getId();
        for (String path : List.of("/api/v1/content-posts/" + f.videoId(),
                "/api/v1/content-posts/" + f.videoId() + "/likers",
                "/api/v1/content-posts/" + f.videoId() + "/comments",
                "/api/v1/comments/" + f.commentId() + "/replies",
                "/api/v1/public/shared-posts/" + f.shareToken())) {
            mvc.perform(as(get(path), u, false)).andExpect(status().isNotFound());
            mvc.perform(as(get(path), u, true)).andExpect(status().isOk());
        }
    }
}
