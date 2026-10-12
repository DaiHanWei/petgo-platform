package com.tailtopia.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.social.dto.FollowListItem;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.repository.UserFollowRepository;
import com.tailtopia.social.service.FollowSuggestionService;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1：推荐关注（V1.3.4 follow · Story 2.3 · AC1）—— 需 Docker postgres+redis。
 *
 * <p>🔴 <b>共享库隔离</b>：测试库不回滚，别的测试留下的公开帖与「此刻」的点赞都会进候选。
 * 排序 / 资格 / 排除类断言因此用<b>固定时钟</b>的服务实例：每个测试取一个远在未来、且逐个递增的基准时刻
 * {@code base}，时钟 = {@code base + 30 天}，统计窗口起点 = {@code base}；本测试的帖子与点赞都落在
 * {@code base} 之后 —— 别人的点赞不在窗口内（全部 0 赞），别人的帖子都比本测试的旧（并列次序排在后面），
 * 本测试造的候选必占前排。鉴权、上限、HTTP 形状才走真实端点。
 */
class FollowSuggestionIntegrationTest extends ApiIntegrationTest {

    /** 每个测试的基准时刻递增，后跑的测试候选永远排在先跑的前面。 */
    private static final AtomicLong BASE_SEQ = new AtomicLong();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserHideRelationService hideService;

    @Autowired
    private VetTestSupport vets;

    @Autowired
    private UserFollowRepository followRepo;

    @Autowired
    private AccountQueryService accounts;

    @Autowired
    private FollowReader followReader;

    @Autowired
    private EntityManagerFactory emf;

    private Instant base;
    private FollowSuggestionService service;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2200-01-01T00:00:00Z").plus(Duration.ofDays(1000 * BASE_SEQ.incrementAndGet()));
        service = new FollowSuggestionService(followRepo, accounts, followReader,
                Clock.fixed(base.plus(Duration.ofDays(30)), ZoneOffset.UTC));
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
        User a = newUser();
        User b = newUser();
        User c = newUser();
        User d = newUser();
        Instant t = base.plus(Duration.ofDays(1));
        likes(publicPost(a, t), 5, base.plus(Duration.ofDays(2)));
        likes(publicPost(b, t), 2, base.plus(Duration.ofDays(2)));
        publicPost(c, t.plus(Duration.ofHours(2)));
        publicPost(d, t.plus(Duration.ofHours(1)));
        // 窗口外 / 不计口径的赞：31 天前、私密帖、审核中帖、软删帖上的赞都不算。
        likes(publicPost(d, base.minus(Duration.ofDays(5))), 9, base.minus(Duration.ofDays(1)));
        likes(post(c, "PRIVATE", "PUBLISHED", t, false), 9, base.plus(Duration.ofDays(2)));
        likes(post(c, "PUBLIC", "UNDER_REVIEW", t, false), 9, base.plus(Duration.ofDays(2)));
        likes(post(c, "PUBLIC", "PUBLISHED", t, true), 9, base.plus(Duration.ofDays(2)));

        assertThat(suggestedIds(viewer)).startsWith(a.getId(), b.getId(), c.getId(), d.getId());
    }

    @Test
    void tieBreaksOnUserIdDescWhenLatestPublicPostIsEqual() {
        User viewer = newUser();
        User first = newUser();
        User second = newUser();
        Instant t = base.plus(Duration.ofDays(1));
        publicPost(first, t);
        publicPost(second, t);

        assertThat(suggestedIds(viewer)).startsWith(second.getId(), first.getId());
    }

    @Test
    void eligibilityRequiresActiveEnabledUserRoleWithLivePublicPost() {
        User viewer = newUser();
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
        Instant t = base.plus(Duration.ofDays(1));
        for (User u : List.of(ok, virtual, vet, admin, disabled, banned, gone)) {
            publicPost(u, t);
        }
        post(onlyPrivate, "PRIVATE", "PUBLISHED", t, false);
        post(onlyDeleted, "PUBLIC", "PUBLISHED", t, true);
        post(onlyUnderReview, "PUBLIC", "UNDER_REVIEW", t, false);
        jdbc.update("UPDATE users SET account_type = 'VIRTUAL' WHERE id = ?", virtual.getId());
        jdbc.update("UPDATE users SET role = 'VET' WHERE id = ?", vet.getId());
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin.getId());
        jdbc.update("UPDATE users SET enabled = false WHERE id = ?", disabled.getId());
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", banned.getId());
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", gone.getId());

        List<Long> ids = suggestedIds(viewer);
        assertThat(ids).contains(ok.getId(), virtual.getId());
        assertThat(ids).doesNotContain(onlyPrivate.getId(), onlyDeleted.getId(), onlyUnderReview.getId(),
                vet.getId(), admin.getId(), disabled.getId(), banned.getId(), gone.getId());
    }

    /** AD-4 / T-26：排除自己、已关注、我拉黑的、我举报隐藏的；拉黑了我的人<b>不排除</b>（单向）。 */
    @Test
    void excludesSelfFollowedAndAnyHideRelationHeldByViewer() {
        User viewer = newUser();
        User followed = newUser();
        User blockedByMe = newUser();
        User reportedByMe = newUser();
        User blockedMe = newUser();
        User plain = newUser();
        Instant t = base.plus(Duration.ofDays(1));
        for (User u : List.of(viewer, followed, blockedByMe, reportedByMe, blockedMe, plain)) {
            publicPost(u, t);
        }
        link(viewer, followed);
        hideService.block(viewer.getId(), blockedByMe.getId());
        hideService.hideByReport(viewer.getId(), reportedByMe.getId());
        hideService.block(blockedMe.getId(), viewer.getId());

        List<Long> ids = suggestedIds(viewer);
        assertThat(ids).contains(plain.getId(), blockedMe.getId());
        assertThat(ids).doesNotContain(viewer.getId(), followed.getId(), blockedByMe.getId(),
                reportedByMe.getId());
    }

    @Test
    void rowFieldsCarryFollowsMeAndConstantFalseFlagsWithFixedQueryCount() {
        User viewer = newUser();
        User fan = newUser();
        User stranger = newUser();
        Instant t = base.plus(Duration.ofDays(1));
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
        for (FollowListItem i : items) {
            assertThat(i.iFollow()).isFalse();
            assertThat(i.self()).isFalse();
            assertThat(i.blockedByMe()).isFalse();
            assertThat(i.deleted()).isFalse();
            assertThat(i.userToken()).hasSize(32);
        }
        FollowListItem fanRow = items.stream().filter(i -> i.userId() == fan.getId()).findFirst().orElseThrow();
        FollowListItem strangerRow = items.stream().filter(i -> i.userId() == stranger.getId()).findFirst()
                .orElseThrow();
        assertThat(fanRow.followsMe()).isTrue();
        assertThat(strangerRow.followsMe()).isFalse();
        assertThat(fanRow.nickname()).isEqualTo(fan.getNickname());
    }

    @Test
    void endpointCapsAtThirtyRows() throws Exception {
        User viewer = newUser();
        Instant t = base.plus(Duration.ofDays(1));
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
