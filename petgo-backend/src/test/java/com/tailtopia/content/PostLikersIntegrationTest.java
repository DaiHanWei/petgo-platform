package com.tailtopia.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.moderation.domain.ReportReason;
import com.tailtopia.moderation.service.ReportService;
import com.tailtopia.social.service.UserHideRelationService;
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

/** L1：点赞人列表 {@code GET /api/v1/content-posts/{id}/likers}（V1.3.4 点赞人抽屉 Task 1）。 */
class PostLikersIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserHideRelationService hideRelations;
    @Autowired
    private ReportService reports;
    @Autowired
    private com.tailtopia.support.VetTestSupport vets;

    private ContentPost savePost(long authorId) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, "t", List.of(), null));
    }

    private void like(long postId, long userId, String createdAt) {
        jdbc.update("INSERT INTO content_likes (post_id, user_id, created_at) VALUES (?, ?, ?::timestamptz)",
                postId, userId, createdAt);
    }

    private String url(long postId) {
        return "/api/v1/content-posts/" + postId + "/likers";
    }

    private JsonNode page(long postId, User viewer, String cursor) throws Exception {
        var req = get(url(postId)).header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId()));
        if (cursor != null) {
            req = req.param("cursor", cursor);
        }
        return json.readTree(mvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static List<Long> ids(JsonNode p) {
        List<Long> l = new ArrayList<>();
        p.get("items").forEach(n -> l.add(n.get("userId").asLong()));
        return l;
    }

    @Test
    void newestFirstWithPagination() throws Exception {
        User viewer = newUser();
        User author = newUser();
        ContentPost post = savePost(author.getId());
        List<Long> expected = new ArrayList<>();
        // 25 人；后 3 人同一 created_at（id 兜底）
        for (int i = 0; i < 25; i++) {
            User u = newUser();
            String ts = i >= 22 ? "2026-10-01T10:00:30Z" : String.format("2026-10-01T10:00:%02dZ", i);
            like(post.getId(), u.getId(), ts);
            expected.add(0, u.getId());
        }
        // 期望：同刻三条按 like id 倒序，其余按时间倒序——等价于 id 倒序（插入序与时间序一致，同刻 id 递增）
        JsonNode p1 = page(post.getId(), viewer, null);
        assertThat(p1.get("items")).hasSize(20);
        assertThat(p1.get("hasMore").asBoolean()).isTrue();
        JsonNode p2 = page(post.getId(), viewer, p1.get("nextCursor").asText());
        assertThat(p2.get("items")).hasSize(5);
        assertThat(p2.get("hasMore").asBoolean()).isFalse();
        List<Long> all = new ArrayList<>(ids(p1));
        all.addAll(ids(p2));
        assertThat(new HashSet<>(all)).hasSize(25);
        assertThat(all).containsExactlyElementsOf(expected);
    }

    @Test
    void excludesBlockedDeletedBanned() throws Exception {
        User viewer = newUser();
        ContentPost post = savePost(newUser().getId());
        User normal = newUser();
        User blocked = newUser();
        User reported = newUser();
        User gone = newUser();
        User banned = newUser();
        long t = 0;
        for (User u : List.of(normal, blocked, reported, gone, banned)) {
            like(post.getId(), u.getId(), String.format("2026-10-01T10:00:%02dZ", t++));
        }
        hideRelations.block(viewer.getId(), blocked.getId());
        hideRelations.hideByReport(viewer.getId(), reported.getId());
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", gone.getId());
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", banned.getId());

        Set<Long> got = new HashSet<>(ids(page(post.getId(), viewer, null)));
        assertThat(got).containsExactlyInAnyOrder(normal.getId(), reported.getId());
    }

    @Test
    void blockedByThemStillShown() throws Exception {
        User viewer = newUser();
        User other = newUser();
        ContentPost post = savePost(newUser().getId());
        like(post.getId(), other.getId(), "2026-10-01T10:00:00Z");
        hideRelations.block(other.getId(), viewer.getId());
        assertThat(ids(page(post.getId(), viewer, null))).containsExactly(other.getId());
    }

    @Test
    void selfRowFlagged() throws Exception {
        User viewer = newUser();
        User a = newUser();
        User b = newUser();
        ContentPost post = savePost(newUser().getId());
        like(post.getId(), a.getId(), "2026-10-01T10:00:00Z");
        like(post.getId(), viewer.getId(), "2026-10-01T10:00:10Z");
        like(post.getId(), b.getId(), "2026-10-01T10:00:20Z");
        JsonNode p = page(post.getId(), viewer, null);
        assertThat(ids(p)).containsExactly(b.getId(), viewer.getId(), a.getId());
        assertThat(p.get("items").get(1).get("self").asBoolean()).isTrue();
        assertThat(p.get("items").get(0).get("self").asBoolean()).isFalse();
    }

    @Test
    void invisiblePost404() throws Exception {
        User viewer = newUser();
        User author = newUser();

        ContentPost deleted = savePost(author.getId());
        deleted.softDelete();
        posts.save(deleted);

        ContentPost priv = savePost(author.getId());
        priv.setVisibility(ContentVisibility.PRIVATE);
        posts.save(priv);

        ContentPost reported = savePost(author.getId());
        reports.submit(reported.getId(), viewer.getId(), ReportReason.values()[0]);

        User blockedAuthor = newUser();
        ContentPost byBlocked = savePost(blockedAuthor.getId());
        hideRelations.block(viewer.getId(), blockedAuthor.getId());

        for (long id : new long[] {deleted.getId(), priv.getId(), reported.getId(), byBlocked.getId(), 99999999L}) {
            mvc.perform(get(url(id)).header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())))
                    .andExpect(status().isNotFound());
        }
        // 私密帖作者本人可看
        mvc.perform(get(url(priv.getId())).header(HttpHeaders.AUTHORIZATION, userBearer(author.getId())))
                .andExpect(status().isOk());
    }

    @Test
    void allFilteredEmptyPage() throws Exception {
        User viewer = newUser();
        ContentPost post = savePost(newUser().getId());
        for (int i = 0; i < 3; i++) {
            User u = newUser();
            like(post.getId(), u.getId(), String.format("2026-10-01T10:00:%02dZ", i));
            hideRelations.block(viewer.getId(), u.getId());
        }
        JsonNode p = page(post.getId(), viewer, null);
        assertThat(p.get("items")).isEmpty();
        assertThat(p.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    void guest401VetForbidden() throws Exception {
        ContentPost post = savePost(newUser().getId());
        mvc.perform(get(url(post.getId()))).andExpect(status().isUnauthorized());
        long vetId = vets.newActiveVet("likers-vet-it").getId();
        mvc.perform(get(url(post.getId())).header(HttpHeaders.AUTHORIZATION, vetBearer(vetId)))
                .andExpect(status().isForbidden());
    }
}
