package com.tailtopia.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.UserTokenGenerator;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.FollowState;
import com.tailtopia.social.service.FollowService;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/**
 * L1：关注 / 取关（V1.3.4 follow · Story 1.1）—— 需 Docker postgres+redis。
 *
 * <p>覆盖 AC2 token / AC3 关注 / AC4 取关 / AC5 批量读一次查询 / AC7 注销清理 / AC8 错误类型。
 * 每日 500 上限在单测 {@code FollowServiceTest}（集成造 500 个用户成本高）。
 */
class FollowIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FollowService followService;

    @Autowired
    private FollowReader followReader;

    @Autowired
    private UserHideRelationService hideService;

    @Autowired
    private VetTestSupport vets;

    @Autowired
    private EntityManagerFactory emf;

    private ResultActions follow(User viewer, String token) throws Exception {
        return mvc.perform(put("/api/v1/users/" + token + "/follow")
                .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())));
    }

    private ResultActions unfollow(User viewer, String token) throws Exception {
        return mvc.perform(delete("/api/v1/users/" + token + "/follow")
                .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())));
    }

    private int followingCount(long userId) {
        return jdbc.queryForObject("SELECT following_count FROM users WHERE id = ?", Integer.class, userId);
    }

    private int followerCount(long userId) {
        return jdbc.queryForObject("SELECT follower_count FROM users WHERE id = ?", Integer.class, userId);
    }

    private long rows(long followerId, long followeeId) {
        return jdbc.queryForObject("SELECT count(*) FROM user_follows WHERE follower_id = ? AND followee_id = ?",
                Long.class, followerId, followeeId);
    }

    // ===== AC3 · 关注 =====

    @Test
    void followIncrementsBothCountersAndIsIdempotent() throws Exception {
        User a = newUser();
        User b = newUser();

        follow(a, b.getPublicToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.iFollow").value(true))
                .andExpect(jsonPath("$.followsMe").value(false))
                .andExpect(jsonPath("$.followerCount").value(1));
        assertThat(followingCount(a.getId())).isEqualTo(1);
        assertThat(followerCount(b.getId())).isEqualTo(1);

        // 重复 PUT：计数不变。
        follow(a, b.getPublicToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.followerCount").value(1));
        assertThat(followingCount(a.getId())).isEqualTo(1);
        assertThat(followerCount(b.getId())).isEqualTo(1);
        assertThat(rows(a.getId(), b.getId())).isOne();
    }

    @Test
    void followsMeReflectsReverseRelation() throws Exception {
        User a = newUser();
        User b = newUser();
        follow(b, a.getPublicToken()).andExpect(status().isOk());

        follow(a, b.getPublicToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.iFollow").value(true))
                .andExpect(jsonPath("$.followsMe").value(true));
    }

    // ===== AC4 · 取关 =====

    @Test
    void unfollowDecrementsAndIsIdempotentAndNeverNegative() throws Exception {
        User a = newUser();
        User b = newUser();
        follow(a, b.getPublicToken()).andExpect(status().isOk());

        unfollow(a, b.getPublicToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.iFollow").value(false))
                .andExpect(jsonPath("$.followerCount").value(0));
        assertThat(followingCount(a.getId())).isZero();
        assertThat(followerCount(b.getId())).isZero();

        // 未关注时静默 200，计数不低于 0。
        unfollow(a, b.getPublicToken()).andExpect(status().isOk());
        assertThat(followingCount(a.getId())).isZero();
        assertThat(followerCount(b.getId())).isZero();
    }

    // ===== 拒绝规则 =====

    @Test
    void followSelfIs422() throws Exception {
        User a = newUser();
        follow(a, a.getPublicToken()).andExpect(status().isUnprocessableEntity());
        unfollow(a, a.getPublicToken()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void followSomeoneIBlockedIs409FollowBlocked() throws Exception {
        User a = newUser();
        User b = newUser();
        hideService.block(a.getId(), b.getId());

        follow(a, b.getPublicToken()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("follow-blocked")));
        assertThat(rows(a.getId(), b.getId())).isZero();
    }

    @Test
    void reportHideDoesNotBlockFollow() throws Exception {
        User a = newUser();
        User b = newUser();
        hideService.hideByReport(a.getId(), b.getId());

        follow(a, b.getPublicToken()).andExpect(status().isOk());
    }

    /** D-4：对方拉黑了我 → 照常成功（不暴露被拉黑），对方粉丝数 +1。 */
    @Test
    void followSomeoneWhoBlockedMeSucceeds() throws Exception {
        User a = newUser();
        User b = newUser();
        hideService.block(b.getId(), a.getId());

        follow(a, b.getPublicToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.iFollow").value(true));
        assertThat(followerCount(b.getId())).isEqualTo(1);
    }

    @Test
    void deletedDeactivatedOrUnknownTargetIs404() throws Exception {
        User a = newUser();
        User gone = newUser();
        gone.anonymizeForDeletion(Instant.now());
        users.save(gone);
        User banned = newUser();
        banned.deactivate();
        users.save(banned);

        follow(a, gone.getPublicToken()).andExpect(status().isNotFound());
        follow(a, banned.getPublicToken()).andExpect(status().isNotFound());
        follow(a, UserTokenGenerator.generate()).andExpect(status().isNotFound());
        follow(a, "short").andExpect(status().isNotFound());
        unfollow(a, gone.getPublicToken()).andExpect(status().isNotFound());
    }

    /** D-8 / AC4.3：封号用户仍在我的关注列表里，必须能取关。 */
    @Test
    void deactivatedUserCanBeUnfollowed() throws Exception {
        User a = newUser();
        User b = newUser();
        follow(a, b.getPublicToken()).andExpect(status().isOk());
        User banned = users.findById(b.getId()).orElseThrow();
        banned.deactivate();
        users.save(banned);

        unfollow(a, b.getPublicToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.iFollow").value(false));
        assertThat(followerCount(b.getId())).isZero();
    }

    @Test
    void guestIs401AndVetIs403() throws Exception {
        User b = newUser();
        mvc.perform(put("/api/v1/users/" + b.getPublicToken() + "/follow"))
                .andExpect(status().isUnauthorized());
        long vetId = vets.newActiveVet("follow-it").getId();
        mvc.perform(put("/api/v1/users/" + b.getPublicToken() + "/follow")
                        .header(HttpHeaders.AUTHORIZATION, vetBearer(vetId)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/users/" + b.getPublicToken() + "/follow")
                        .header(HttpHeaders.AUTHORIZATION, vetBearer(vetId)))
                .andExpect(status().isForbidden());
    }

    /** T-1：关注与取关共用每分钟 30 次的桶，第 31 次 429。 */
    @Test
    void thirtyFirstOperationInAMinuteIs429() throws Exception {
        User a = newUser();
        User b = newUser();
        for (int i = 0; i < 30; i++) {
            if (i % 2 == 0) {
                follow(a, b.getPublicToken()).andExpect(status().isOk());
            } else {
                unfollow(a, b.getPublicToken()).andExpect(status().isOk());
            }
        }
        follow(a, b.getPublicToken()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("rate-limited")));
    }

    // ===== AC7 · 注销清理 =====

    @Test
    void purgeUserRemovesBothDirectionsAndDecrementsCounterparts() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        follow(a, b.getPublicToken()).andExpect(status().isOk());
        follow(c, a.getPublicToken()).andExpect(status().isOk());

        followService.purgeUser(a.getId());

        assertThat(followerCount(b.getId())).isZero();
        assertThat(followingCount(c.getId())).isZero();
        assertThat(followingCount(a.getId())).isZero();
        assertThat(followerCount(a.getId())).isZero();
        assertThat(rows(a.getId(), b.getId())).isZero();
        assertThat(rows(c.getId(), a.getId())).isZero();

        // 重跑幂等。
        followService.purgeUser(a.getId());
        assertThat(followerCount(b.getId())).isZero();
        assertThat(followingCount(c.getId())).isZero();
    }

    // ===== AC2 · token =====

    @Test
    void newUsersGetDistinct32CharTokensIncludingJdbcInserted() {
        User a = newUser();
        User b = newUser();
        assertThat(a.getPublicToken()).hasSize(32).matches("[0-9a-zA-Z]{32}");
        assertThat(b.getPublicToken()).hasSize(32).isNotEqualTo(a.getPublicToken());

        Long id = jdbc.queryForObject(
                "INSERT INTO users (nickname, status) VALUES ('jdbc-follow-it', 'ACTIVE') RETURNING id", Long.class);
        String token = jdbc.queryForObject("SELECT public_token FROM users WHERE id = ?", String.class, id);
        assertThat(token).hasSize(32).matches("[0-9a-zA-Z]{32}");
    }

    // ===== AC5 · 批量读一次查询 =====

    @Test
    void followStatesCoversWholeBatchInOneQuery() throws Exception {
        User me = newUser();
        User x = newUser();
        User y = newUser();
        User z = newUser();
        follow(me, x.getPublicToken()).andExpect(status().isOk());
        follow(y, me.getPublicToken()).andExpect(status().isOk());

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        Map<Long, FollowState> states = followReader.followStates(me.getId(),
                List.of(x.getId(), y.getId(), z.getId()));
        long statements = stats.getPrepareStatementCount();
        stats.setStatisticsEnabled(false);

        assertThat(statements).as("整批一次查询").isEqualTo(1);
        assertThat(states.get(x.getId())).isEqualTo(new FollowState(true, false));
        assertThat(states.get(y.getId())).isEqualTo(new FollowState(false, true));
        assertThat(states.get(z.getId())).isEqualTo(FollowState.NONE);
        assertThat(followReader.followStates(me.getId(), List.of())).isEmpty();
    }
}
