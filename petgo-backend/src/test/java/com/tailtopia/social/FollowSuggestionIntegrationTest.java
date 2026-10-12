package com.tailtopia.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.social.dto.FollowListItem;
import com.tailtopia.social.service.FollowSuggestionService;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1：推荐关注（V1.3.4 follow · Story 2.3 · AC1）—— 需 Docker postgres+redis。
 *
 * <p>🔴 <b>共享库隔离</b>：测试库不回滚、跨运行保留，别的测试（以及本类上一次运行）留下的公开帖与点赞都会进候选。
 * 排序 / 资格 / 排除类用例先 {@link #isolate}：让 viewer <b>关注此刻库里全部公开发帖人</b>（已关注 = 被推荐排除），
 * 之后本用例新造的候选就是 viewer 唯一可能看到的人 —— 断言可以写成「结果恰好等于」，漏过滤会直接现形。
 * 不用固定时钟 / 未来时间戳：未来时间的公开帖会永久顶在别的测试的 Feed 首位（2.3 code-review F1）。
 * 用例全部走真实 bean（{@code FollowSuggestionService} 的 Spring 代理与构造器注入一并覆盖）。
 */
class FollowSuggestionIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserHideRelationService hideService;

    @Autowired
    private VetTestSupport vets;

    @Autowired
    private FollowSuggestionService service;

    @Autowired
    private EntityManagerFactory emf;

    private final Instant now = Instant.now();

    /** viewer 关注此刻库里所有公开发帖人（jdbc 直插，不动计数列）。须在造候选<b>之前</b>调用。 */
    private void isolate(User viewer) {
        jdbc.update("""
                INSERT INTO user_follows (follower_id, followee_id)
                SELECT ?, p.author_id
                  FROM (SELECT DISTINCT author_id FROM content_posts
                         WHERE visibility = 'PUBLIC' AND status = 'PUBLISHED' AND deleted_at IS NULL) p
                 WHERE p.author_id <> ?
                ON CONFLICT DO NOTHING
                """, viewer.getId(), viewer.getId());
    }

    private long post(User author, String visibility, String status, Instant createdAt, boolean deleted) {
        return jdbc.queryForObject("INSERT INTO content_posts (author_id, type, status, visibility, created_at,"
                + " updated_at, deleted_at, content_version) VALUES (?, 'DAILY', ?, ?, ?, ?, ?, 1) RETURNING id",
                Long.class, author.getId(), status, visibility, Timestamp.from(createdAt), Timestamp.from(createdAt),
                deleted ? Timestamp.from(createdAt) : null);
    }

    private long publicPost(User author, Instant createdAt) {
        return post(author, "PUBLIC", "PUBLISHED", createdAt, false);
    }

    private void likes(long postId, int n, Instant at) {
        for (int i = 0; i < n; i++) {
            jdbc.update("INSERT INTO content_likes (post_id, user_id, created_at) VALUES (?, ?, ?)",
                    postId, newUser().getId(), Timestamp.from(at));
        }
    }

    private void link(User follower, User followee) {
        jdbc.update("INSERT INTO user_follows (follower_id, followee_id) VALUES (?, ?)",
                follower.getId(), followee.getId());
    }

    private List<Long> suggestedIds(User viewer) {
        return service.suggestionsFor(viewer.getId()).items().stream().map(FollowListItem::userId).toList();
    }

    @Test
    void ordersByLikesInWindowThenLatestPublicPostThenIdDesc() {
        User viewer = newUser();
        isolate(viewer);
        User a = newUser();
        User b = newUser();
        User c = newUser();
        User d = newUser();
        Instant t = now.minus(Duration.ofDays(3));
        likes(publicPost(a, t), 5, now.minus(Duration.ofDays(1)));
        likes(publicPost(b, t), 2, now.minus(Duration.ofDays(1)));
        publicPost(c, t.plus(Duration.ofHours(2)));
        publicPost(d, t.plus(Duration.ofHours(1)));
        // 不计口径的赞：31 天前的、私密帖 / 审核中帖 / 软删帖上的。
        likes(publicPost(d, now.minus(Duration.ofDays(40))), 9, now.minus(Duration.ofDays(31)));
        likes(post(c, "PRIVATE", "PUBLISHED", t, false), 9, now.minus(Duration.ofDays(1)));
        likes(post(c, "PUBLIC", "UNDER_REVIEW", t, false), 9, now.minus(Duration.ofDays(1)));
        likes(post(c, "PUBLIC", "PUBLISHED", t, true), 9, now.minus(Duration.ofDays(1)));

        assertThat(suggestedIds(viewer)).containsExactly(a.getId(), b.getId(), c.getId(), d.getId());
    }

    @Test
    void tieBreaksOnUserIdDescWhenLatestPublicPostIsEqual() {
        User viewer = newUser();
        isolate(viewer);
        User first = newUser();
        User second = newUser();
        Instant t = now.minus(Duration.ofDays(1));
        publicPost(first, t);
        publicPost(second, t);

        assertThat(suggestedIds(viewer)).containsExactly(second.getId(), first.getId());
    }

    /**
     * 不合格的人帖子<b>更新</b>、id 也更大 —— 一旦漏过滤必排在合格者前面，「恰好等于」立刻失败（2.3 code-review F2）。
     */
    @Test
    void eligibilityRequiresActiveEnabledUserRoleWithLivePublicPost() {
        User viewer = newUser();
        isolate(viewer);
        User ok = newUser();
        User virtual = newUser();
        User onlyPrivate = newUser();
        User onlyDeleted = newUser();
        User onlyUnderReview = newUser();
        User vet = newUser();
        User admin = newUser();
        User disabled = newUser();
        User banned = newUser();
        User gone = newUser();
        Instant t = now.minus(Duration.ofDays(1));
        Instant later = t.plus(Duration.ofHours(1));
        publicPost(ok, t);
        publicPost(virtual, t);
        for (User u : List.of(vet, admin, disabled, banned, gone)) {
            publicPost(u, later);
        }
        post(onlyPrivate, "PRIVATE", "PUBLISHED", later, false);
        post(onlyDeleted, "PUBLIC", "PUBLISHED", later, true);
        post(onlyUnderReview, "PUBLIC", "UNDER_REVIEW", later, false);
        jdbc.update("UPDATE users SET account_type = 'VIRTUAL' WHERE id = ?", virtual.getId());
        jdbc.update("UPDATE users SET role = 'VET' WHERE id = ?", vet.getId());
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin.getId());
        jdbc.update("UPDATE users SET enabled = false WHERE id = ?", disabled.getId());
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", banned.getId());
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", gone.getId());

        assertThat(suggestedIds(viewer)).containsExactly(virtual.getId(), ok.getId());
    }

    /** AD-4 / T-26：排除自己、已关注、我拉黑的、我举报隐藏的；拉黑了我的人<b>不排除</b>（单向）。 */
    @Test
    void excludesSelfFollowedAndAnyHideRelationHeldByViewer() {
        User viewer = newUser();
        isolate(viewer);
        User plain = newUser();
        User blockedMe = newUser();
        User followed = newUser();
        User blockedByMe = newUser();
        User reportedByMe = newUser();
        Instant t = now.minus(Duration.ofDays(1));
        publicPost(plain, t);
        publicPost(blockedMe, t);
        // 被排除的人帖子更新：漏网必排在前面。
        for (User u : List.of(viewer, followed, blockedByMe, reportedByMe)) {
            publicPost(u, t.plus(Duration.ofHours(1)));
        }
        link(viewer, followed);
        hideService.block(viewer.getId(), blockedByMe.getId());
        hideService.hideByReport(viewer.getId(), reportedByMe.getId());
        hideService.block(blockedMe.getId(), viewer.getId());

        assertThat(suggestedIds(viewer)).containsExactly(blockedMe.getId(), plain.getId());
    }

    @Test
    void rowFieldsCarryFollowsMeAndConstantFalseFlagsWithFixedQueryCount() {
        User viewer = newUser();
        isolate(viewer);
        User fan = newUser();
        User stranger = newUser();
        Instant t = now.minus(Duration.ofDays(1));
        publicPost(fan, t);
        publicPost(stranger, t);
        link(fan, viewer);

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        List<FollowListItem> items = service.suggestionsFor(viewer.getId()).items();
        long statements = stats.getPrepareStatementCount();
        stats.setStatisticsEnabled(false);

        assertThat(statements).as("候选 + 展示字段 + token + 关系，固定次数（NFR3）").isLessThanOrEqualTo(4);
        assertThat(items).extracting(FollowListItem::userId).containsExactly(stranger.getId(), fan.getId());
        for (FollowListItem i : items) {
            assertThat(i.iFollow()).isFalse();
            assertThat(i.self()).isFalse();
            assertThat(i.blockedByMe()).isFalse();
            assertThat(i.deleted()).isFalse();
            assertThat(i.userToken()).hasSize(32);
        }
        assertThat(items.get(1).followsMe()).isTrue();
        assertThat(items.get(0).followsMe()).isFalse();
        assertThat(items.get(1).nickname()).isEqualTo(fan.getNickname());
    }

    @Test
    void endpointCapsAtThirtyRows() throws Exception {
        User viewer = newUser();
        Instant t = now.minus(Duration.ofDays(1));
        for (int i = 0; i < 35; i++) {
            publicPost(newUser(), t);
        }
        String body = mvc.perform(get("/api/v1/me/follow-suggestions")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode items = json.readTree(body).get("items");
        assertThat(items).hasSize(30);
        assertThat(json.readTree(body).has("nextCursor")).as("无分页、无游标").isFalse();
        JsonNode first = items.get(0);
        for (String key : List.of("userId", "userToken", "iFollow", "followsMe", "blockedByMe", "self", "deleted")) {
            assertThat(first.has(key)).as(key).isTrue();
        }
    }

    @Test
    void guestIs401AndVetIs403() throws Exception {
        mvc.perform(get("/api/v1/me/follow-suggestions")).andExpect(status().isUnauthorized());
        long vetId = vets.newActiveVet("follow-suggest-it").getId();
        mvc.perform(get("/api/v1/me/follow-suggestions").header(HttpHeaders.AUTHORIZATION, vetBearer(vetId)))
                .andExpect(status().isForbidden());
    }
}
