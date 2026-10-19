package com.tailtopia.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.event.UserSignedUpEvent;
import com.tailtopia.social.service.FollowService;
import com.tailtopia.social.service.OfficialAutoFollowService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** L1：新用户自动关注官方号 + 取关记录（V1.3.4 following 流 · Task 2）。 */
class OfficialAutoFollowIntegrationTest extends ApiIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OfficialAutoFollowService autoFollow;
    @Autowired private FollowService followService;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager txManager;

    private User newOfficial() {
        long n = SEQ.incrementAndGet();
        return users.save(User.newAdmin("official-" + n + "@t.test", "官方" + n, "{bcrypt}x"));
    }

    private long rows(long follower, long followee) {
        return jdbc.queryForObject("SELECT count(*) FROM user_follows WHERE follower_id = ? AND followee_id = ?",
                Long.class, follower, followee);
    }

    private int col(String c, long id) {
        return jdbc.queryForObject("SELECT " + c + " FROM users WHERE id = ?", Integer.class, id);
    }

    @Test
    void newUserFollowsOfficialAccount() {
        User official = newOfficial();
        User u = newUser();
        autoFollow.followOfficialAccounts(u.getId());

        assertThat(jdbc.queryForObject("SELECT source FROM user_follows WHERE follower_id = ? AND followee_id = ?",
                String.class, u.getId(), official.getId())).isEqualTo("SYSTEM");
        assertThat(col("follower_count", official.getId())).isEqualTo(1);
        assertThat(col("following_count", u.getId())).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'USER_FOLLOWED' AND recipient_user_id = ?",
                Long.class, official.getId())).isZero();
    }

    @Test
    void idempotent() {
        User official = newOfficial();
        User u = newUser();
        autoFollow.followOfficialAccounts(u.getId());
        int followingAfterFirst = col("following_count", u.getId());
        autoFollow.followOfficialAccounts(u.getId());

        assertThat(rows(u.getId(), official.getId())).isEqualTo(1);
        assertThat(col("follower_count", official.getId())).isEqualTo(1);
        assertThat(col("following_count", u.getId())).isEqualTo(followingAfterFirst);
    }

    @Test
    void unfollowOfficialRecordsOptoutAndBlocksReFollow() {
        User official = newOfficial();
        User u = newUser();
        autoFollow.followOfficialAccounts(u.getId());

        followService.unfollow(u.getId(), official.getPublicToken());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM official_follow_optouts WHERE user_id = ? AND official_id = ?",
                Long.class, u.getId(), official.getId())).isEqualTo(1);
        autoFollow.followOfficialAccounts(u.getId());
        assertThat(rows(u.getId(), official.getId())).isZero();
    }

    @Test
    void unfollowOrdinaryUserRecordsNoOptout() {
        User a = newUser();
        User b = newUser();
        followService.follow(a.getId(), b.getPublicToken());
        followService.unfollow(a.getId(), b.getPublicToken());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM official_follow_optouts WHERE user_id = ?",
                Long.class, a.getId())).isZero();
    }

    @Test
    void blockedOfficialIsSkipped() {
        User official = newOfficial();
        User u = newUser();
        // 应用层 block() 只接受 USER 目标，这里直接落 BLOCK 行以验证 isBlocked 跳过逻辑
        jdbc.update("INSERT INTO user_hide_relations (holder_id, target_id, source) VALUES (?, ?, 'BLOCK')",
                u.getId(), official.getId());
        autoFollow.followOfficialAccounts(u.getId());
        assertThat(rows(u.getId(), official.getId())).isZero();
    }

    @Test
    void nonUserRolesSkipped() {
        User official = newOfficial();
        User otherOfficial = newOfficial();
        autoFollow.followOfficialAccounts(otherOfficial.getId());
        assertThat(rows(otherOfficial.getId(), official.getId())).isZero();

        long n = SEQ.incrementAndGet();
        User vet = User.newGoogleUser("it-vet-" + n, "vet" + n + "@petgo.test", "兽医" + n, null);
        vet.setNickname("兽医" + n);
        vet.setOnboardingCompleted(true);
        vet = users.save(vet);
        jdbc.update("UPDATE users SET role = 'VET' WHERE id = ?", vet.getId());
        autoFollow.followOfficialAccounts(vet.getId());
        assertThat(rows(vet.getId(), official.getId())).isZero();
        assertThat(Role.valueOf(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class,
                vet.getId()))).isEqualTo(Role.VET);
    }

    @Test
    void listenerRunsOnSignup() throws Exception {
        User official = newOfficial();
        User u = newUser();
        new TransactionTemplate(txManager).executeWithoutResult(
                st -> publisher.publishEvent(new UserSignedUpEvent(u.getId(), u.getNickname())));

        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (rows(u.getId(), official.getId()) == 0 && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }
        assertThat(rows(u.getId(), official.getId())).isEqualTo(1);
    }

    private User newVirtualUser() {
        long n = SEQ.incrementAndGet();
        User v = users.save(User.newVirtual("virtual-sub-" + n, "马甲" + n, null, 1L));
        return v;
    }

    @Test
    void virtualUserNotAutoFollowedOnSignup() {
        User official = newOfficial();
        User v = newVirtualUser();
        autoFollow.followOfficialAccounts(v.getId());
        assertThat(rows(v.getId(), official.getId())).isZero();
    }

    /** I-2：重跑迁移的回填段（INSERT + UPDATE），VIRTUAL 不得被回填，REAL 被回填。 */
    @Test
    void backfillSqlExcludesVirtualUsers() throws Exception {
        User official = newOfficial();
        User real = newUser();
        User virt = newVirtualUser();
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/db/migration/V20261014_1600__following_feed_official_follow.sql"));
        String backfill = sql.substring(sql.indexOf("INSERT INTO user_follows"));
        for (String stmt : backfill.split(";\\s*\\n")) {
            if (stmt.replaceAll("--[^\\n]*", "").isBlank()) {
                continue;
            }
            jdbc.execute(stmt);
        }
        assertThat(rows(real.getId(), official.getId())).isEqualTo(1);
        assertThat(rows(virt.getId(), official.getId())).isZero();
    }
}
