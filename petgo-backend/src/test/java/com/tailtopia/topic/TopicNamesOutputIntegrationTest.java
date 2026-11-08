package com.tailtopia.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.topic.domain.TopicNameRules;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * V1.4.2 social-topic · Story 4.1 · AC6.2 · L1（需 postgres + redis；云端不跑，待本地验收）。
 *
 * <p>同一帖在 Discover 分类 Tab、详情、他人主页、我的发布里都带 {@code topicNames}（标准名）；DISABLED 后不含、
 * HIDDEN 仍含；无话题的帖无该键。关注流 / 顶置卡 / 宠物访客时间线走同一批量口（{@code assemble} / 
 * {@code loadPinnedSlot} / {@code VisitorProjectionService}），由本地 L1 扩充验收。
 */
class TopicNamesOutputIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private static String uniq(String base) {
        String s = UUID.randomUUID().toString().replaceAll("[^a-f]", "");
        return base + s.substring(0, Math.min(10, s.length()));
    }

    private long publish(long userId, String text) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("type", "DAILY", "text", text));
        String resp = mvc.perform(post("/api/v1/content-posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    private JsonNode getJson(String url, long viewer) throws Exception {
        return json.readTree(mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, userBearer(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static JsonNode item(JsonNode page, long postId) {
        for (JsonNode i : page.get("items")) {
            if (i.get("id").asLong() == postId) {
                return i;
            }
        }
        throw new AssertionError("post " + postId + " not in page");
    }

    @Test
    void topicNamesOnEveryOutletAndStatusFiltering() throws Exception {
        long author = newUser().getId();
        long viewer = newUser().getId();
        String name = uniq("Anabul");
        String norm = TopicNameRules.normalize(name);
        long postId = publish(author, "#" + name + " lucu");
        long plain = publish(author, "tanpa topik");

        assertThat(getJson("/api/v1/content-posts/" + postId + "?countView=false", viewer).get("topicNames"))
                .extracting(JsonNode::asText).containsExactly(norm);
        assertThat(item(getJson("/api/v1/content-posts?category=DAILY", viewer), postId).get("topicNames"))
                .extracting(JsonNode::asText).containsExactly(norm);
        assertThat(item(getJson("/api/v1/users/" + author + "/posts", viewer), postId).get("topicNames"))
                .extracting(JsonNode::asText).containsExactly(norm);
        assertThat(item(getJson("/api/v1/me/posts", author), postId).get("topicNames"))
                .extracting(JsonNode::asText).containsExactly(norm);
        assertThat(item(getJson("/api/v1/me/posts", author), plain).has("topicNames")).isFalse();

        jdbc.update("UPDATE topics SET status = 'HIDDEN' WHERE name_norm = ?", norm);
        assertThat(getJson("/api/v1/content-posts/" + postId + "?countView=false", viewer).has("topicNames")).isTrue();

        jdbc.update("UPDATE topics SET status = 'DISABLED' WHERE name_norm = ?", norm);
        assertThat(getJson("/api/v1/content-posts/" + postId + "?countView=false", viewer).has("topicNames")).isFalse();
        assertThat(item(getJson("/api/v1/me/posts", author), postId).has("topicNames")).isFalse();
    }
}
