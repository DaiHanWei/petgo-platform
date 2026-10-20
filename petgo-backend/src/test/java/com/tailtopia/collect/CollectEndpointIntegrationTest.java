package com.tailtopia.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.account.service.AccountDeletionService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.event.ContentCollectedEvent;
import com.tailtopia.content.event.ContentUncollectedEvent;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * L1（需 Docker postgres + redis，云端不跑）：V1.4.0 ④ Story 2.1 —— 帖子收藏端点、事件、详情字段、安全、注销。
 */
@RecordApplicationEvents
class CollectEndpointIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApplicationEvents events;
    @Autowired
    private UserHideRelationService hides;
    @Autowired
    private VetTestSupport vets;
    @Autowired
    private AccountDeletionRepository deletions;
    @Autowired
    private AccountDeletionService accountDeletion;
    @Autowired
    private com.tailtopia.place.repository.PlaceRepository places;

    private ContentPost publicPost(User author) {
        return posts.save(ContentPost.publish(author.getId(), ContentType.DAILY, null, "正文", List.of()));
    }

    private void collect(User u, long postId, int expect) throws Exception {
        mvc.perform(post("/api/v1/content-posts/" + postId + "/collect")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().is(expect));
    }

    private void uncollect(User u, long postId) throws Exception {
        mvc.perform(delete("/api/v1/content-posts/" + postId + "/collect")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collected").value(false));
    }

    private long rows(long postId) {
        return jdbc.queryForObject("SELECT count(*) FROM content_collections WHERE post_id = ?", Long.class, postId);
    }

    private long collected(long postId, long collectorId) {
        return events.stream(ContentCollectedEvent.class)
                .filter(e -> e.postId() == postId && e.collectorId() == collectorId).count();
    }

    private long uncollected(long postId, long collectorId) {
        return events.stream(ContentUncollectedEvent.class)
                .filter(e -> e.postId() == postId && e.collectorId() == collectorId).count();
    }

    @Test
    @DisplayName("A 收藏 B 公开帖：1 行、1 次事件、无通知；重复收藏幂等不再发")
    void collectOnceAndIdempotent() throws Exception {
        User a = newUser();
        User b = newUser();
        long postId = publicPost(b).getId();
        long notifBefore = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ?",
                Long.class, b.getId());

        collect(a, postId, 200);
        collect(a, postId, 200);

        assertThat(rows(postId)).isEqualTo(1);
        assertThat(collected(postId, a.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ?",
                Long.class, b.getId())).isEqualTo(notifBefore);
    }

    @Test
    @DisplayName("自收藏也发事件；作者详情 collectCount 不计自己，他人详情无该键，游客 / 兽医同游客")
    void detailFields() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        long postId = publicPost(b).getId();
        collect(b, postId, 200);
        collect(a, postId, 200);
        collect(c, postId, 200);
        assertThat(events.stream(ContentCollectedEvent.class)
                .filter(e -> e.postId() == postId && e.collectorId() == b.getId() && e.authorId() == b.getId())
                .count()).isEqualTo(1);

        mvc.perform(get("/api/v1/content-posts/" + postId).header(HttpHeaders.AUTHORIZATION, userBearer(b.getId())))
                .andExpect(jsonPath("$.collectCount").value(2))
                .andExpect(jsonPath("$.collected").value(true));
        mvc.perform(get("/api/v1/content-posts/" + postId).header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andExpect(jsonPath("$.collectCount").doesNotExist())
                .andExpect(jsonPath("$.collected").value(true));
        mvc.perform(get("/api/v1/content-posts/" + postId))
                .andExpect(jsonPath("$.collectCount").doesNotExist())
                .andExpect(jsonPath("$.collected").value(false));
        String vet = vetBearer(vets.newActiveVet("collect-it").getId());
        mvc.perform(get("/api/v1/content-posts/" + postId).header(HttpHeaders.AUTHORIZATION, vet))
                .andExpect(jsonPath("$.collectCount").doesNotExist())
                .andExpect(jsonPath("$.collected").value(false));
    }

    @Test
    @DisplayName("私密帖 422 collect-not-public；软删帖 404；都不写库")
    void privateAndDeleted() throws Exception {
        User a = newUser();
        ContentPost priv = ContentPost.publish(a.getId(), ContentType.DAILY, null, "私密", List.of());
        priv.setVisibility(ContentVisibility.PRIVATE);
        long privId = posts.save(priv).getId();
        mvc.perform(post("/api/v1/content-posts/" + privId + "/collect")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/collect-not-public"));
        ContentPost gone = publicPost(newUser());
        gone.softDelete();
        posts.save(gone);
        collect(a, gone.getId(), 404);
        assertThat(rows(privId) + rows(gone.getId())).isZero();
    }

    @Test
    @DisplayName("取消：可见 → 发 Uncollected；软删后取消 / 作者拉黑我后取消 → 删行不发")
    void uncollectEvents() throws Exception {
        User a = newUser();
        User b = newUser();
        long p1 = publicPost(b).getId();
        long p2 = publicPost(b).getId();
        long p3 = publicPost(b).getId();
        collect(a, p1, 200);
        collect(a, p2, 200);
        collect(a, p3, 200);

        uncollect(a, p1);
        assertThat(uncollected(p1, a.getId())).isEqualTo(1);

        ContentPost post2 = posts.findById(p2).orElseThrow();
        post2.softDelete();
        posts.save(post2);
        uncollect(a, p2);
        assertThat(rows(p2)).isZero();
        assertThat(uncollected(p2, a.getId())).isZero();

        hides.block(b.getId(), a.getId());
        uncollect(a, p3);
        assertThat(rows(p3)).isZero();
        assertThat(uncollected(p3, a.getId())).isZero();

        uncollect(a, 987_654_321L); // 不存在也 200
    }

    @Test
    @DisplayName("游客 401、兽医 403（帖子与场所四个端点）")
    void security() throws Exception {
        long postId = publicPost(newUser()).getId();
        String vet = vetBearer(vets.newActiveVet("collect-it-sec").getId());
        for (String url : List.of("/api/v1/content-posts/" + postId + "/collect", "/api/v1/places/whatever/collect")) {
            mvc.perform(post(url)).andExpect(status().isUnauthorized());
            mvc.perform(delete(url)).andExpect(status().isUnauthorized());
            mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, vet)).andExpect(status().isForbidden());
            mvc.perform(delete(url).header(HttpHeaders.AUTHORIZATION, vet)).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("注销：本人两表行清零；他人收藏指向其帖子的行保留")
    void accountDeletion() throws Exception {
        User a = newUser();
        User b = newUser();
        long bPost = publicPost(b).getId();
        long aPost = publicPost(a).getId();
        collect(a, bPost, 200);
        collect(b, aPost, 200);
        String placeToken = PlaceCollectIntegrationTest.newPlace(places, a).getPublicToken();
        mvc.perform(post("/api/v1/places/" + placeToken + "/collect")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andExpect(status().isOk());

        AccountDeletion d = deletions.save(AccountDeletion.request(a.getId()));
        accountDeletion.execute(d.getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_collections WHERE user_id = ?", Long.class,
                a.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM place_collections WHERE user_id = ?", Long.class,
                a.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_collections WHERE user_id = ? AND post_id = ?",
                Long.class, b.getId(), aPost)).isEqualTo(1);
    }
}
