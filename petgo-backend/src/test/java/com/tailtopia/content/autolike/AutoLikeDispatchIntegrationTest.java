package com.tailtopia.content.autolike;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.autocomment.VirtualIdentityPicker;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.LikeService;
import com.tailtopia.content.species.ContentSpeciesResolver;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * L1 · V1.5.0 likes Story 2.3（A-AC16 ~ AC29、AC35 ~ AC38、AC45）：派发真跑 SQL 与点赞链路，拨钟。
 * 需真 PG + Redis（云端不跑，本地验收）。每例清空自动点赞三张表；共享库别的行只按本例 id 断言。
 */
class AutoLikeDispatchIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NamedParameterJdbcTemplate named;
    @Autowired
    private PlatformTransactionManager txm;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private AutoLikeProperties props;
    @Autowired
    private AutoLikeVirtualPool pool;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private ContentLikeRepository likes;
    @Autowired
    private ContentSpeciesResolver speciesResolver;
    @Autowired
    private VirtualIdentityPicker picker;
    @Autowired
    private LikeService likeService;

    private final Instant runStart = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private long runId;
    private boolean savedEnabled;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM auto_like_logs");
        jdbc.update("DELETE FROM auto_like_runs");
        jdbc.update("DELETE FROM auto_like_targets");
        runId = jdbc.queryForObject("INSERT INTO auto_like_runs (run_date, slot, started_at, window_end, status, launch)"
                + " VALUES (current_date, 'MORNING', ?, ?, 'ACTIVE', false) RETURNING id", Long.class,
                Timestamp.from(runStart), Timestamp.from(runStart.plus(Duration.ofHours(3))));
        savedEnabled = props.isEnabled();
        props.setEnabled(true); // dev profile 默认关；本类直接调 dispatchOnce 验证开着时的行为
    }

    @org.junit.jupiter.api.AfterEach
    void restore() {
        props.setEnabled(savedEnabled);
    }

    private AutoLikeDispatcher at(Instant now) {
        return new AutoLikeDispatcher(named, txm, redis, props, pool, posts, userRepo, likes, speciesResolver, picker,
                likeService, Clock.fixed(now, ZoneOffset.UTC));
    }

    private User virtual() {
        User v = newUser();
        jdbc.update("UPDATE users SET account_type = 'VIRTUAL', google_sub = 'virtual:' || google_sub WHERE id = ?",
                v.getId());
        return v;
    }

    private long target(User author, int existingLikes) {
        ContentPost p = posts.save(ContentPost.publish(author.getId(), ContentType.DAILY, null, "t", List.of(), null));
        for (int i = 0; i < existingLikes; i++) {
            jdbc.update("INSERT INTO content_likes (post_id, user_id) VALUES (?, ?)", p.getId(), newUser().getId());
        }
        jdbc.update("INSERT INTO auto_like_targets (post_id, author_id, post_created_at, checked_at, like_count_at_check,"
                + " joined) SELECT id, author_id, created_at, now(), ?, true FROM content_posts WHERE id = ?",
                existingLikes, p.getId());
        return p.getId();
    }

    private long plan(long postId, Duration offset) {
        return jdbc.queryForObject("INSERT INTO auto_like_logs (run_id, post_id, run_date, slot, planned_at, window_end,"
                + " status) VALUES (?, ?, current_date, 'MORNING', ?, ?, 'PLANNED') RETURNING id", Long.class, runId,
                postId, Timestamp.from(runStart.plus(offset)), Timestamp.from(runStart.plus(Duration.ofHours(3))));
    }

    private String logStatus(long logId) {
        return jdbc.queryForObject("SELECT status FROM auto_like_logs WHERE id = ?", String.class, logId);
    }

    private String leaveReason(long postId) {
        return jdbc.queryForObject("SELECT leave_reason FROM auto_like_targets WHERE post_id = ?", String.class, postId);
    }

    @Test
    void likedLooksLikeARealLikeToTheAuthor() {
        virtual();
        User author = newUser();
        long post = target(author, 2);
        long log = plan(post, Duration.ofMinutes(5));

        at(runStart.plus(Duration.ofMinutes(10))).dispatchOnce();

        assertThat(logStatus(log)).isEqualTo("LIKED");
        assertThat(jdbc.queryForObject("SELECT like_count_before FROM auto_like_logs WHERE id = ?", Integer.class, log))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_likes WHERE post_id = ? AND is_automated",
                Long.class, post)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT last_liked_at FROM auto_like_targets WHERE post_id = ?",
                Timestamp.class, post)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ?"
                + " AND type = 'CONTENT_LIKED'", Long.class, author.getId())).isEqualTo(1L);
    }

    @Test
    void fifthLikeLeavesAsReachedTargetAndNeverExceedsFive() {
        virtual();
        long post = target(newUser(), 4);
        long log = plan(post, Duration.ZERO);
        at(runStart.plus(Duration.ofMinutes(1))).dispatchOnce();
        assertThat(logStatus(log)).isEqualTo("LIKED");
        assertThat(leaveReason(post)).isEqualTo("REACHED_TARGET");
        assertThat(likes.countByPostId(post)).isEqualTo(5L);
    }

    @Test
    void alreadyAtTargetOnDispatchDoesNotLike() {
        virtual();
        long post = target(newUser(), 5);
        long log = plan(post, Duration.ZERO);
        at(runStart.plus(Duration.ofMinutes(1))).dispatchOnce();
        assertThat(logStatus(log)).isEqualTo("REACHED_TARGET");
        assertThat(likes.countByPostId(post)).isEqualTo(5L);
    }

    @Test
    void underReviewIsSkippedAndGoneIsClosed() {
        virtual();
        long review = target(newUser(), 0);
        jdbc.update("UPDATE content_posts SET status = 'UNDER_REVIEW' WHERE id = ?", review);
        long gone = target(newUser(), 0);
        jdbc.update("UPDATE content_posts SET deleted_at = now() WHERE id = ?", gone);
        long l1 = plan(review, Duration.ZERO);
        long l2 = plan(gone, Duration.ZERO);

        at(runStart.plus(Duration.ofMinutes(1))).dispatchOnce();

        assertThat(logStatus(l1)).isEqualTo("SKIPPED_REVIEW");
        assertThat(leaveReason(review)).isNull();
        assertThat(logStatus(l2)).isEqualTo("POST_GONE");
        assertThat(leaveReason(gone)).isEqualTo("POST_GONE");
    }

    /** A-AC45：拨到 11:00 → 之前到期的全发；拨到窗口后 → 未发的全 MISSED，失败数不变。 */
    @Test
    void catchUpThenMissAfterWindow() {
        virtual();
        long early = target(newUser(), 0);
        long late = target(newUser(), 0);
        long l1 = plan(early, Duration.ofMinutes(20));
        long l2 = plan(late, Duration.ofMinutes(150));

        at(runStart.plus(Duration.ofMinutes(30))).dispatchOnce();
        assertThat(logStatus(l1)).isEqualTo("LIKED");
        assertThat(logStatus(l2)).isEqualTo("PLANNED");

        at(runStart.plus(Duration.ofHours(4))).dispatchOnce();
        assertThat(logStatus(l2)).isEqualTo("MISSED");
        assertThat(leaveReason(late)).isNull();
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM auto_like_targets WHERE post_id = ?",
                Integer.class, late)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM auto_like_runs WHERE id = ?", String.class, runId))
                .isEqualTo("DONE");
    }

    /** A-AC25：所有虚拟号都赞过 → NO_IDENTITY 离开。 */
    @Test
    void allVirtualAccountsAlreadyLikedLeavesNoIdentity() {
        long post = target(newUser(), 0);
        for (User v : pool.load()) {
            jdbc.update("INSERT INTO content_likes (post_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", post,
                    v.getId());
        }
        // 若号池里号很多会超过 5 赞而先走 REACHED_TARGET；两者都满足「不再点」
        long log = plan(post, Duration.ZERO);
        at(runStart.plus(Duration.ofMinutes(1))).dispatchOnce();
        assertThat(logStatus(log)).isIn("NO_IDENTITY", "REACHED_TARGET");
    }
}
