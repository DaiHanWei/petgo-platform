package com.tailtopia.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/** L1：关注流 {@code GET /api/v1/me/following-feed}（V1.3.4 Following 流 Task 3）。 */
class FollowingFeedIntegrationTest extends ApiIntegrationTest {

    private static final String URL = "/api/v1/me/following-feed";

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.tailtopia.support.VetTestSupport vets;

    private ContentPost savePost(long authorId, String text) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, text, List.of(), null));
    }

    private void follow(long follower, long followee) {
        jdbc.update("INSERT INTO user_follows (follower_id, followee_id) VALUES (?, ?)", follower, followee);
    }

    private JsonNode page(User viewer, String cursor) throws Exception {
        var req = get(URL).header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId()));
        if (cursor != null) {
            req = req.param("cursor", cursor);
        }
        String body = mvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static Set<Long> ids(JsonNode page) {
        Set<Long> s = new HashSet<>();
        page.get("items").forEach(n -> s.add(n.get("id").asLong()));
        return s;
    }

    @Test
    void onlyFollowedAuthorsPublicPostsNewestFirst() throws Exception {
        User viewer = newUser();
        User a = newUser();
        User b = newUser();
        follow(viewer.getId(), a.getId());

        ContentPost older = savePost(a.getId(), "a-old");
        ContentPost priv = savePost(a.getId(), "a-private");
        priv.setVisibility(ContentVisibility.PRIVATE);
        posts.save(priv);
        ContentPost deleted = savePost(a.getId(), "a-deleted");
        deleted.softDelete();
        posts.save(deleted);
        ContentPost newer = savePost(a.getId(), "a-new");
        ContentPost notFollowed = savePost(b.getId(), "b-public");

        JsonNode p = page(viewer, null);

        List<Long> order = new ArrayList<>();
        p.get("items").forEach(n -> order.add(n.get("id").asLong()));
        assertThat(order).containsExactly(newer.getId(), older.getId());
        assertThat(order).doesNotContain(priv.getId(), deleted.getId(), notFollowed.getId());
        assertThat(p.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    void excludesReportHiddenAndBannedAuthors() throws Exception {
        User viewer = newUser();
        User c = newUser();
        User d = newUser();
        User ok = newUser();
        follow(viewer.getId(), c.getId());
        follow(viewer.getId(), d.getId());
        follow(viewer.getId(), ok.getId());
        jdbc.update("INSERT INTO user_hide_relations (holder_id, target_id, source) VALUES (?, ?, 'REPORT')",
                viewer.getId(), c.getId());
        User dd = users.findById(d.getId()).orElseThrow();
        dd.deactivate();
        users.save(dd);

        ContentPost pc = savePost(c.getId(), "c");
        ContentPost pd = savePost(d.getId(), "d");
        ContentPost po = savePost(ok.getId(), "ok");

        Set<Long> got = ids(page(viewer, null));

        assertThat(got).contains(po.getId()).doesNotContain(pc.getId(), pd.getId());
    }

    @Test
    void excludesPostsIReported() throws Exception {
        User viewer = newUser();
        User a = newUser();
        follow(viewer.getId(), a.getId());
        ContentPost reported = savePost(a.getId(), "reported");
        ContentPost fine = savePost(a.getId(), "fine");
        jdbc.update("INSERT INTO content_reports (post_id, reporter_id, reason_type) VALUES (?, ?, 'OTHER')",
                reported.getId(), viewer.getId());

        Set<Long> got = ids(page(viewer, null));

        assertThat(got).containsExactly(fine.getId());
    }

    @Test
    void paginationNoDupNoGap() throws Exception {
        User viewer = newUser();
        User a = newUser();
        follow(viewer.getId(), a.getId());
        Set<Long> created = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            created.add(savePost(a.getId(), "p" + i).getId());
        }
        jdbc.update("UPDATE content_posts SET created_at = TIMESTAMPTZ '2026-01-01 00:00:00+00' WHERE author_id = ?",
                a.getId());

        JsonNode first = page(viewer, null);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        JsonNode second = page(viewer, first.get("nextCursor").asText());

        List<Long> all = new ArrayList<>();
        first.get("items").forEach(n -> all.add(n.get("id").asLong()));
        second.get("items").forEach(n -> all.add(n.get("id").asLong()));
        assertThat(all).hasSize(25).doesNotHaveDuplicates();
        assertThat(new HashSet<>(all)).isEqualTo(created);
        assertThat(second.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    void guestGets401() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void vetTokenIs403() throws Exception {
        long vetId = vets.newActiveVet("feed-vet-it").getId();
        mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, vetBearer(vetId)))
                .andExpect(status().isForbidden());
    }
}
