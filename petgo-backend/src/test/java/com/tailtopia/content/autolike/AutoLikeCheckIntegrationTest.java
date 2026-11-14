package com.tailtopia.content.autolike;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1 · V1.5.0 likes Story 2.1（A-AC4 ~ AC13、AC47、AC50）：检查步骤真跑 SQL。需真 PG（云端不跑，本地验收）。
 *
 * <p>首轮判定看「表是否为空」，所以每例先清空 {@code auto_like_targets}；共享库里别的帖子也可能入表，断言只看本例造的帖。
 */
class AutoLikeCheckIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AutoLikeCheckStep step;
    @Autowired
    private AutoLikeTargetRepository targets;
    @Autowired
    private AutoLikeHoldout holdout;
    @Autowired
    private TransactionTemplate tx;

    private final Instant run = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM auto_like_targets");
    }

    private User author(boolean wantHoldout) {
        for (int i = 0; i < 200; i++) {
            User u = newUser();
            if (holdout.isHoldout(u.getId()) == wantHoldout) {
                return u;
            }
        }
        throw new IllegalStateException("no user with holdout=" + wantHoldout);
    }

    private long postAged(User author, Duration age) {
        ContentPost p = posts.save(ContentPost.publish(author.getId(), ContentType.DAILY, null, "t", List.of(), null));
        jdbc.update("UPDATE content_posts SET created_at = ? WHERE id = ?", Timestamp.from(run.minus(age)), p.getId());
        return p.getId();
    }

    private void likes(long postId, int n) {
        for (int i = 0; i < n; i++) {
            jdbc.update("INSERT INTO content_likes (post_id, user_id) VALUES (?, ?)", postId, newUser().getId());
        }
    }

    private AutoLikeCheckStep.CheckResult runStep() {
        return tx.execute(s -> step.run(run));
    }

    private Optional<AutoLikeTarget> row(long postId) {
        return targets.findByPostId(postId);
    }

    /** 让表非空，后续轮次为普通轮。 */
    private void notLaunch() {
        long seed = postAged(author(false), Duration.ofHours(40));
        jdbc.update("INSERT INTO auto_like_targets (post_id, author_id, post_created_at, checked_at, like_count_at_check,"
                + " joined, left_at, leave_reason) SELECT id, author_id, created_at, now(), 0, false, now(), 'HOLDOUT'"
                + " FROM content_posts WHERE id = ?", seed);
    }

    @Test
    void windowBoundariesOnNormalRun() {
        notLaunch();
        User a = author(false);
        long at36 = postAged(a, Duration.ofHours(36));
        long at35 = postAged(a, Duration.ofHours(35));
        long at60 = postAged(a, Duration.ofHours(60));
        long at50 = postAged(a, Duration.ofHours(50));

        AutoLikeCheckStep.CheckResult r = runStep();

        assertThat(r.launch()).isFalse();
        assertThat(row(at36)).get().extracting(AutoLikeTarget::isJoined).isEqualTo(true);
        assertThat(row(at50)).isPresent();
        assertThat(row(at35)).isEmpty();
        assertThat(row(at60)).isEmpty();
    }

    @Test
    void decisionsAtTargetHoldoutAndJoined() {
        notLaunch();
        User a = author(false);
        long cold = postAged(a, Duration.ofHours(40));
        likes(cold, 4);
        long warm = postAged(a, Duration.ofHours(40));
        likes(warm, 5);
        long held = postAged(author(true), Duration.ofHours(40));

        runStep();

        AutoLikeTarget c = row(cold).orElseThrow();
        assertThat(c.isJoined()).isTrue();
        assertThat(c.getLeftAt()).isNull();
        assertThat(c.getLikeCountAtCheck()).isEqualTo(4);
        AutoLikeTarget w = row(warm).orElseThrow();
        assertThat(w.isJoined()).isFalse();
        assertThat(w.getLeaveReason()).isEqualTo(AutoLikeLeaveReason.AT_TARGET_ON_CHECK);
        AutoLikeTarget h = row(held).orElseThrow();
        assertThat(h.isJoined()).isFalse();
        assertThat(h.getLeaveReason()).isEqualTo(AutoLikeLeaveReason.HOLDOUT);
        assertThat(h.getLeftAt()).isEqualTo(h.getCheckedAt());
    }

    @Test
    void eachPostCheckedOnlyOnce() {
        notLaunch();
        long p = postAged(author(false), Duration.ofHours(40));
        runStep();
        jdbc.update("UPDATE auto_like_targets SET left_at = now(), leave_reason = 'REACHED_TARGET' WHERE post_id = ?", p);
        runStep();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auto_like_targets WHERE post_id = ?", Long.class, p))
                .isEqualTo(1L);
        assertThat(row(p).orElseThrow().getLeaveReason()).isEqualTo(AutoLikeLeaveReason.REACHED_TARGET);
    }

    @Test
    void ineligiblePostsAreNotChecked() {
        notLaunch();
        long priv = postAged(author(false), Duration.ofHours(40));
        jdbc.update("UPDATE content_posts SET visibility = 'PRIVATE' WHERE id = ?", priv);
        long review = postAged(author(false), Duration.ofHours(40));
        jdbc.update("UPDATE content_posts SET status = 'UNDER_REVIEW' WHERE id = ?", review);
        long deleted = postAged(author(false), Duration.ofHours(40));
        jdbc.update("UPDATE content_posts SET deleted_at = now() WHERE id = ?", deleted);
        User virtual = author(false);
        jdbc.update("UPDATE users SET account_type = 'VIRTUAL', google_sub = 'virtual:' || google_sub WHERE id = ?",
                virtual.getId());
        long byVirtual = postAged(virtual, Duration.ofHours(40));
        User admin = author(false);
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin.getId());
        long byAdmin = postAged(admin, Duration.ofHours(40));
        User deact = author(false);
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", deact.getId());
        long byDeact = postAged(deact, Duration.ofHours(40));

        runStep();

        for (long id : List.of(priv, review, deleted, byVirtual, byAdmin, byDeact)) {
            assertThat(row(id)).as("post " + id).isEmpty();
        }
    }

    /** A-AC12：审核中本轮不查；恢复后仍在窗口内 → 下一轮被查。 */
    @Test
    void underReviewIsCheckedAfterRestoreWithinWindow() {
        notLaunch();
        long p = postAged(author(false), Duration.ofHours(40));
        jdbc.update("UPDATE content_posts SET status = 'UNDER_REVIEW' WHERE id = ?", p);
        runStep();
        assertThat(row(p)).isEmpty();
        jdbc.update("UPDATE content_posts SET status = 'PUBLISHED' WHERE id = ?", p);
        runStep();
        assertThat(row(p)).isPresent();
    }

    /** A-AC9 / AC10：首轮（表空）上限 7 天；50h、6 天进，8 天、30h 不查。 */
    @Test
    void launchRunUsesSevenDayUpperBound() {
        User a = author(false);
        long h50 = postAged(a, Duration.ofHours(50));
        likes(h50, 1);
        long d6 = postAged(a, Duration.ofDays(6));
        likes(d6, 1);
        long d8 = postAged(a, Duration.ofDays(8));
        long h30 = postAged(a, Duration.ofHours(30));

        AutoLikeCheckStep.CheckResult r = runStep();

        assertThat(r.launch()).isTrue();
        assertThat(row(h50)).get().extracting(AutoLikeTarget::isJoined).isEqualTo(true);
        assertThat(row(d6)).get().extracting(AutoLikeTarget::isJoined).isEqualTo(true);
        assertThat(row(d8)).isEmpty();
        assertThat(row(h30)).isEmpty();
        assertThat(runStep().launch()).isFalse();
    }

    /** A-AC50 / Q-4：超龄 → MAX_AGE；失败次数到上限 → TOO_MANY_FAILURES。 */
    @Test
    void closesMaxAgeAndTooManyFailures() {
        notLaunch();
        User a = author(false);
        long old = postAged(a, Duration.ofHours(40));
        long failing = postAged(a, Duration.ofHours(40));
        runStep();
        jdbc.update("UPDATE auto_like_targets SET post_created_at = ? WHERE post_id = ?",
                Timestamp.from(run.minus(Duration.ofDays(7))), old);
        jdbc.update("UPDATE auto_like_targets SET failed_attempts = 3 WHERE post_id = ?", failing);

        AutoLikeCheckStep.CheckResult r = runStep();

        assertThat(r.closedMaxAge()).isGreaterThanOrEqualTo(1);
        assertThat(r.closedFailures()).isGreaterThanOrEqualTo(1);
        assertThat(row(old).orElseThrow().getLeaveReason()).isEqualTo(AutoLikeLeaveReason.MAX_AGE);
        assertThat(row(failing).orElseThrow().getLeaveReason()).isEqualTo(AutoLikeLeaveReason.TOO_MANY_FAILURES);
    }
}
