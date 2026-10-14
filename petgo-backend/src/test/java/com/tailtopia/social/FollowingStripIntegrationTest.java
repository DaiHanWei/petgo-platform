package com.tailtopia.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/** L1：关注头像排接口 GET /api/v1/me/following-strip（V1.3.4 following 流 · Task 4）。 */
class FollowingStripIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserHideRelationService hideService;

    private final Instant now = Instant.now();

    private void link(User follower, User followee, Instant at) {
        jdbc.update("INSERT INTO user_follows (follower_id, followee_id, created_at) VALUES (?, ?, ?)",
                follower.getId(), followee.getId(), Timestamp.from(at));
    }

    private long post(User author, String visibility, String status, Instant at, boolean deleted) {
        return jdbc.queryForObject("INSERT INTO content_posts (author_id, type, status, visibility, created_at,"
                + " updated_at, deleted_at, content_version) VALUES (?, 'DAILY', ?, ?, ?, ?, ?, 1) RETURNING id",
                Long.class, author.getId(), status, visibility, Timestamp.from(at), Timestamp.from(at),
                deleted ? Timestamp.from(at) : null);
    }

    private JsonNode strip(User viewer) throws Exception {
        String body = mvc.perform(get("/api/v1/me/following-strip")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("items");
    }

    private List<Long> ids(JsonNode items) {
        List<Long> out = new ArrayList<>();
        items.forEach(n -> out.add(n.get("userId").asLong()));
        return out;
    }

    @Test
    void orderByLatestVisiblePostThenFollowTime() throws Exception {
        User viewer = newUser();
        User newest = newUser();
        User older = newUser();
        User silentLate = newUser();
        User silentEarly = newUser();
        link(viewer, older, now.minus(Duration.ofDays(5)));
        link(viewer, newest, now.minus(Duration.ofDays(6)));
        link(viewer, silentEarly, now.minus(Duration.ofDays(4)));
        link(viewer, silentLate, now.minus(Duration.ofDays(1)));
        jdbc.update("UPDATE users SET avatar_url = 'https://img.test/a.png' WHERE id = ?", newest.getId());
        post(newest, "PUBLIC", "PUBLISHED", now.minus(Duration.ofHours(1)), false);
        post(older, "PUBLIC", "PUBLISHED", now.minus(Duration.ofDays(2)), false);
        // 不可见帖不参与排序：silentLate 只有私密 / 审核中 / 软删帖，仍算「未发帖」
        post(silentLate, "PRIVATE", "PUBLISHED", now, false);
        post(silentLate, "PUBLIC", "UNDER_REVIEW", now, false);
        post(silentLate, "PUBLIC", "PUBLISHED", now, true);

        JsonNode items = strip(viewer);
        assertThat(ids(items)).containsExactly(newest.getId(), older.getId(), silentLate.getId(),
                silentEarly.getId());
        JsonNode first = items.get(0);
        assertThat(first.get("userToken").asText()).isNotBlank();
        assertThat(first.get("avatarUrl").asText()).isEqualTo("https://img.test/a.png");
    }

    @Test
    void capsAt20() throws Exception {
        User viewer = newUser();
        for (int i = 0; i < 25; i++) {
            link(viewer, newUser(), now.minus(Duration.ofMinutes(i)));
        }
        assertThat(strip(viewer)).hasSize(20);
    }

    @Test
    void excludesBannedDeletedAndHidden() throws Exception {
        User viewer = newUser();
        User ok = newUser();
        User banned = newUser();
        User gone = newUser();
        User blocked = newUser();
        for (User u : List.of(ok, banned, gone, blocked)) {
            link(viewer, u, now.minus(Duration.ofDays(1)));
        }
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", banned.getId());
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", gone.getId());
        hideService.block(viewer.getId(), blocked.getId());

        assertThat(ids(strip(viewer))).containsExactly(ok.getId());
    }

    @Test
    void guestGets401() throws Exception {
        mvc.perform(get("/api/v1/me/following-strip")).andExpect(status().isUnauthorized());
    }
}
