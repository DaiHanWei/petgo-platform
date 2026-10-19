package com.tailtopia.growth;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.growth.domain.ReverseReason;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.service.AccrualCommand;
import com.tailtopia.growth.service.GrowthIdemKeys;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.growth.service.ReverseCommand;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** V1.4.0 ① Story 1.1 · AC3 / AC8 / AC17（L1，需 Docker postgres）：真库上的 accrue / reverse 与并发上限。 */
class GrowthLedgerIntegrationTest extends ApiIntegrationTest {

    private static final Instant NOON_WIB = Instant.parse("2026-10-18T05:00:00Z");

    @Autowired
    private GrowthLedgerService ledger;

    @Autowired
    private JdbcTemplate jdbc;

    private long lifetime(long userId) {
        Long v = jdbc.queryForObject("SELECT COALESCE((SELECT lifetime_total FROM growth_accounts WHERE user_id = ?), 0)",
                Long.class, userId);
        return v == null ? 0 : v;
    }

    private int rows(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE user_id = ?", Integer.class, userId);
    }

    private AccrualCommand passive(long userId, long postId, long liker, Instant at) {
        return new AccrualCommand(userId, RuleCode.LIKE_RECEIVED, null, GrowthIdemKeys.likeReceived(liker, postId),
                "CONTENT_POST", String.valueOf(postId), null, at);
    }

    private void concurrently(int threads, int perThread, IntFunction<Runnable> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            int base = t * perThread;
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    task.apply(base + i).run();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void accrueThenReverseAppendsAndNeverEdits() {
        User u = newUser();
        assertThat(ledger.accrue(passive(u.getId(), 1L, 2L, NOON_WIB))).isTrue();
        assertThat(ledger.accrue(passive(u.getId(), 1L, 2L, NOON_WIB))).isFalse(); // 幂等
        assertThat(ledger.reverse(new ReverseCommand(GrowthIdemKeys.likeReceived(2L, 1L), ReverseReason.UNLIKE,
                GrowthIdemKeys.SOURCE_UNLIKE, null))).isTrue();
        assertThat(ledger.reverse(new ReverseCommand(GrowthIdemKeys.likeReceived(2L, 1L), ReverseReason.UNLIKE,
                GrowthIdemKeys.SOURCE_UNLIKE, null))).isFalse();

        assertThat(rows(u.getId())).isEqualTo(2);
        assertThat(lifetime(u.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT delta FROM growth_ledger WHERE user_id = ? AND reverses_id IS NULL",
                Integer.class, u.getId())).isEqualTo(1);
    }

    @Test
    void passiveConcurrentAccrualStopsAtExactlyFifty() throws Exception {
        long author = newUser().getId();
        concurrently(2, 30, i -> () -> ledger.accrue(passive(author, 1000L + i, 99L, NOON_WIB)));

        assertThat(lifetime(author)).isEqualTo(50);
    }

    @Test
    void concurrentPostsOnlyThreeCount() throws Exception {
        long author = newUser().getId();
        concurrently(5, 1, i -> () -> ledger.accrue(new AccrualCommand(author, RuleCode.POST_PUBLISHED, null,
                GrowthIdemKeys.post(5_000_000L + author * 10 + i), "CONTENT_POST", "x", null, NOON_WIB)));

        assertThat(lifetime(author)).isEqualTo(15);
    }

    @Test
    void sameKeyConcurrentlyOnlyOnce() throws Exception {
        long author = newUser().getId();
        concurrently(2, 1, i -> () -> ledger.accrue(passive(author, 1L, 3L, NOON_WIB)));

        assertThat(lifetime(author)).isEqualTo(1);
    }

    @Test
    void wibMidnightSplitsTheQuotaDay() {
        long u = newUser().getId();
        Instant before = Instant.parse("2026-10-18T16:59:00Z");
        Instant after = Instant.parse("2026-10-18T17:00:00Z");
        for (int i = 0; i < 3; i++) {
            ledger.accrue(new AccrualCommand(u, RuleCode.POST_PUBLISHED, null, GrowthIdemKeys.post(7_000_000L + u * 10 + i),
                    null, null, null, before));
        }
        assertThat(ledger.accrue(new AccrualCommand(u, RuleCode.POST_PUBLISHED, null,
                GrowthIdemKeys.post(7_000_000L + u * 10 + 5), null, null, null, before))).isFalse();
        assertThat(ledger.accrue(new AccrualCommand(u, RuleCode.POST_PUBLISHED, null,
                GrowthIdemKeys.post(7_000_000L + u * 10 + 6), null, null, null, after))).isTrue();
    }
}
