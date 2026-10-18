package com.tailtopia.growth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1：选头像框（V1.4.0 ① Story 3.3 · AC1 / AC2）—— 需 Docker postgres+redis，<b>云端不跑，待本地验收</b>。
 *
 * <p>本地跑法：{@code DB_NAME=<新库> ./mvnw -B test -Dtest=MeGrowthFrameIntegrationTest}（Redis 不 FLUSH）。
 */
class MeGrowthFrameIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private User userAtLevel(int level) {
        User u = newUser();
        jdbc.update("INSERT INTO growth_accounts (user_id, lifetime_total, level, version, created_at, updated_at)"
                + " VALUES (?, 0, ?, 0, now(), now())", u.getId(), level);
        return u;
    }

    private JsonNode putFrame(User u, String frame, int expectedStatus) throws Exception {
        String body = mvc.perform(put("/api/v1/me/growth/frame").header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"frame\":\"" + frame + "\"}"))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode getJson(User u, String path) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String frameChoice(User u) {
        return jdbc.queryForObject("SELECT frame_choice FROM growth_accounts WHERE user_id = ?", String.class, u.getId());
    }

    @Test
    void chooseBasicIsVisibleEverywhere() throws Exception {
        User u = userAtLevel(10);
        JsonNode frames = putFrame(u, "BASIC", 200);
        assertThat(frames.get("effective").asText()).isEqualTo("BASIC");
        assertThat(frames.get("manual").asBoolean()).isTrue();

        assertThat(getJson(u, "/api/v1/me").get("growth").get("frame").asText()).isEqualTo("BASIC");

        Instant at = Instant.now();
        long postId = jdbc.queryForObject("INSERT INTO content_posts (author_id, type, status, visibility, created_at,"
                + " updated_at, content_version) VALUES (?, 'DAILY', 'PUBLISHED', 'PUBLIC', ?, ?, 1) RETURNING id",
                Long.class, u.getId(), Timestamp.from(at), Timestamp.from(at));
        User viewer = newUser();
        assertThat(getJson(viewer, "/api/v1/content-posts/" + postId).get("authorFrame").asText()).isEqualTo("BASIC");
    }

    @Test
    void lockedFrameIs409AndColumnUnchanged() throws Exception {
        User u = userAtLevel(10);
        putFrame(u, "BASIC", 200);
        JsonNode problem = putFrame(u, "ANIMATED", 409);
        assertThat(problem.get("type").asText()).endsWith("/frame-locked");
        assertThat(frameChoice(u)).isEqualTo("BASIC");
    }

    @Test
    void noneMeansNoFrameAndManual() throws Exception {
        User u = userAtLevel(10);
        putFrame(u, "NONE", 200);
        JsonNode page = getJson(u, "/api/v1/me/growth");
        assertThat(page.get("frames").get("effective").asText()).isEqualTo("NONE");
        assertThat(page.get("frames").get("manual").asBoolean()).isTrue();
    }

    @Test
    void downgradeFallsBackToHighestAvailableWithoutRewritingChoice() throws Exception {
        User u = userAtLevel(10);
        putFrame(u, "UPGRADED", 200);
        jdbc.update("UPDATE growth_accounts SET level = 9 WHERE user_id = ?", u.getId()); // 模拟退款降级
        assertThat(getJson(u, "/api/v1/me/growth").get("frames").get("effective").asText()).isEqualTo("BASIC");
        assertThat(frameChoice(u)).isEqualTo("UPGRADED");
    }

    @Test
    void invalidValueIs422() throws Exception {
        putFrame(userAtLevel(10), "GOLD", 422);
    }
}
