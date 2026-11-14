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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1 · V1.5.0 likes Story 2.2（A-AC2、AC14、AC15、AC43、AC44）：计划真跑 SQL。需真 PG（云端不跑，本地验收）。
 * 共享库里别的帖子也可能被检查 / 排期，断言只看本例造的帖。
 */
class AutoLikePlanIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AutoLikePlanner planner;
    @Autowired
    private AutoLikeHoldout holdout;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM auto_like_logs");
        jdbc.update("DELETE FROM auto_like_runs");
        jdbc.update("DELETE FROM auto_like_targets");
        User v = newUser();
        jdbc.update("UPDATE users SET account_type = 'VIRTUAL', google_sub = 'virtual:' || google_sub WHERE id = ?",
                v.getId());
        // 让表非空：后续轮次为普通轮（首轮会把 7 天内的共享库帖子全检查一遍，拖慢测试）
        long seed = post(author(), Duration.ofHours(40));
        jdbc.update("INSERT INTO auto_like_targets (post_id, author_id, post_created_at, checked_at, like_count_at_check,"
                + " joined, left_at, leave_reason) SELECT id, author_id, created_at, now(), 0, false, now(), 'HOLDOUT'"
                + " FROM content_posts WHERE id = ?", seed);
    }

    private User author() {
        for (int i = 0; i < 200; i++) {
            User u = newUser();
            if (!holdout.isHoldout(u.getId())) {
                return u;
            }
        }
        throw new IllegalStateException("no non-holdout user");
    }

    private long post(User author, Duration age) {
        ContentPost p = posts.save(ContentPost.publish(author.getId(), ContentType.DAILY, null, "t", List.of(), null));
        jdbc.update("UPDATE content_posts SET created_at = ? WHERE id = ?", Timestamp.from(now.minus(age)), p.getId());
        return p.getId();
    }

    private void join(long postId, Instant lastLikedAt) {
        jdbc.update("INSERT INTO auto_like_targets (post_id, author_id, post_created_at, checked_at, like_count_at_check,"
                + " joined, last_liked_at) SELECT id, author_id, created_at, now(), 0, true, ? FROM content_posts"
                + " WHERE id = ?", lastLikedAt == null ? null : Timestamp.from(lastLikedAt), postId);
    }

    private List<Long> plannedOf(List<Long> postIds, long runId) {
        List<Long> out = new ArrayList<>();
        for (Long id : postIds) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM auto_like_logs WHERE run_id = ? AND post_id = ?",
                    Long.class, runId, id);
            if (n != null && n > 0) {
                out.add(id);
            }
        }
        return out;
    }

    private long runId(Instant at, AutoLikeSlot slot) {
        return jdbc.queryForObject("SELECT id FROM auto_like_runs WHERE started_at = ? AND slot = ?", Long.class,
                Timestamp.from(at), slot.name());
    }

    /** A-AC43 / AC44：作者 3 帖 → 每轮 1 帖；3 轮内每帖各一次。 */
    @Test
    void oneAuthorThreePostsRotateAcrossRuns() {
        User a = author();
        long p1 = post(a, Duration.ofDays(3));
        long p2 = post(a, Duration.ofDays(4));
        long p3 = post(a, Duration.ofDays(5));
        join(p1, null);
        join(p2, null);
        join(p3, null);
        List<Long> mine = List.of(p1, p2, p3);
        List<Long> seen = new ArrayList<>();
        Instant t = now;
        for (int round = 0; round < 3; round++) {
            AutoLikeSlot slot = round % 2 == 0 ? AutoLikeSlot.MORNING : AutoLikeSlot.EVENING;
            Instant at = t.plus(Duration.ofDays(round)); // 不同日期 = 不同槽，也不与上一轮窗口重叠
            planner.plan(slot, at);
            List<Long> planned = plannedOf(mine, runId(at, slot));
            assertThat(planned).hasSize(1);
            seen.add(planned.get(0));
            // 模拟 2.3 点赞成功
            jdbc.update("UPDATE auto_like_targets SET last_liked_at = ? WHERE post_id = ?", Timestamp.from(at),
                    planned.get(0));
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(mine);
        assertThat(seen.get(0)).isEqualTo(p3); // 最老的先
    }

    /** A-AC15：planned_at ∈ [now, now + 3h)，顺序与帖子 id 无关。 */
    @Test
    void plannedTimesAreRandomInsideWindow() {
        List<Long> mine = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            long p = post(author(), Duration.ofHours(48));
            join(p, null);
            mine.add(p);
        }
        planner.plan(AutoLikeSlot.MORNING, now);
        long runId = runId(now, AutoLikeSlot.MORNING);
        List<Timestamp> times = new ArrayList<>();
        for (Long id : mine) {
            times.add(jdbc.queryForObject("SELECT planned_at FROM auto_like_logs WHERE run_id = ? AND post_id = ?",
                    Timestamp.class, runId, id));
        }
        assertThat(times).allSatisfy(ts -> assertThat(ts.toInstant())
                .isAfterOrEqualTo(now).isBefore(now.plus(Duration.ofHours(3))));
        List<Timestamp> sorted = new ArrayList<>(times);
        sorted.sort(null);
        assertThat(times).isNotEqualTo(sorted);
    }

    /** A-AC2：两实例同时计划同一槽 → 一条 run、每帖一条 PLANNED。 */
    @Test
    void concurrentPlansProduceOneRun() throws Exception {
        long p = post(author(), Duration.ofHours(48));
        join(p, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<AutoLikePlanner.PlanResult> c = () -> planner.plan(AutoLikeSlot.MORNING, now);
            Future<AutoLikePlanner.PlanResult> f1 = pool.submit(c);
            Future<AutoLikePlanner.PlanResult> f2 = pool.submit(c);
            AutoLikePlanner.PlanResult r1 = f1.get();
            AutoLikePlanner.PlanResult r2 = f2.get();
            assertThat(List.of(r1, r2)).filteredOn(r -> r.skipped() == null).hasSize(1);
            assertThat(List.of(r1, r2)).filteredOn(r -> r.skipped() != null)
                    .allSatisfy(r -> assertThat(r.skipped()).isIn(AutoLikePlanner.SkipReason.LOCKED,
                            AutoLikePlanner.SkipReason.ALREADY_PLANNED));
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auto_like_runs WHERE started_at = ?", Long.class,
                Timestamp.from(now))).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auto_like_logs WHERE post_id = ?", Long.class, p))
                .isEqualTo(1L);
    }

    /** A-AC1：上一轮窗口未结束 → 本轮不写 run 行。 */
    @Test
    void overlappingRunIsSkipped() {
        planner.plan(AutoLikeSlot.MORNING, now);
        AutoLikePlanner.PlanResult r = planner.plan(AutoLikeSlot.EVENING, now.plus(Duration.ofHours(1)));
        assertThat(r.skipped()).isEqualTo(AutoLikePlanner.SkipReason.OVERLAP);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auto_like_runs", Long.class)).isEqualTo(1L);
    }

    /** A-AC14：不设每轮上限——1,200 位作者各 1 帖全部排上。 */
    @Test
    void noPerRunCap() {
        List<Long> mine = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            long p = post(newUser(), Duration.ofHours(48));
            join(p, null);
            mine.add(p);
        }
        planner.plan(AutoLikeSlot.MORNING, now);
        long runId = runId(now, AutoLikeSlot.MORNING);
        assertThat(plannedOf(mine, runId)).hasSize(1200);
    }
}
