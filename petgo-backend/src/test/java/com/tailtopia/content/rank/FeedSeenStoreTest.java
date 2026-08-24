package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * L0：曝光记录（Story 16.1 · AC1 / AC2 / AC4 / AC5）。
 *
 * <p>沿用平台既有 Redis 单测写法（mock {@link StringRedisTemplate}，见 {@code AdminLoginThrottleTest}）——
 * 真 Redis 往返另有 {@code FeedRankStoresIntegrationTest} 在 L1 覆盖。
 */
class FeedSeenStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-24T10:00:00Z");

    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zset;
    private FeedRankProperties props;
    private FeedSeenStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        props = new FeedRankProperties(0.3, 7, 30, 100);
        store = new FeedSeenStore(redis, props);
    }

    // ── AC1 / AC2：曝光衰减系数 ──────────────────────────────────────

    @Test
    void seenContentGetsDecayAndUnseenGetsOne() {
        FeedRankCacheKey key = FeedRankCacheKey.forUser(1L);
        // id=10 窗口内曝光过；id=11 从未曝光 —— ZMSCORE 对未命中成员返回 null，
        // 所以这里必须用 ArrayList 而不是 List.of（后者不允许 null 元素）。
        List<Double> scores = new java.util.ArrayList<>();
        scores.add((double) NOW.toEpochMilli());
        scores.add(null);
        when(zset.score("feed:seen:u:1", "10", "11")).thenReturn(scores);

        Map<Long, Double> f = store.decayFactors(key, List.of(10L, 11L), NOW);

        assertThat(f.get(10L)).isEqualTo(0.3);
        assertThat(f.get(11L)).isEqualTo(1.0);
    }

    /**
     * 🔴 这条是「用 ZSET 而不是 SET」那处偏离的全部理由：出了 7 天窗口的曝光必须回到 1.0。
     *
     * <p>若改回 SET + 键 TTL 续期，天天刷首页的用户永远命中「已曝光」，曝光衰减这一维被抹平 ——
     * 而那不会报错，只会让排序悄悄失真。
     */
    @Test
    void exposureOlderThanWindowNoLongerDecays() {
        FeedRankCacheKey key = FeedRankCacheKey.forUser(1L);
        double eightDaysAgo = NOW.minus(Duration.ofDays(8)).toEpochMilli();
        List<Double> scores = new java.util.ArrayList<>();
        scores.add(eightDaysAgo);
        when(zset.score("feed:seen:u:1", new Object[] {"10"})).thenReturn(scores);

        assertThat(store.decayFactors(key, List.of(10L), NOW).get(10L)).isEqualTo(1.0);
    }

    /** 🛡 一次 ZMSCORE 批量取完，不逐条查。 */
    @Test
    void readsAllScoresInOneCall() {
        FeedRankCacheKey key = FeedRankCacheKey.forUser(1L);
        List<Double> scores = new java.util.ArrayList<>();
        scores.add(null);
        scores.add(null);
        scores.add(null);
        when(zset.score("feed:seen:u:1", "1", "2", "3")).thenReturn(scores);

        store.decayFactors(key, List.of(1L, 2L, 3L), NOW);

        verify(zset).score(anyString(), any(Object[].class));
    }

    // ── AC4：游客 ───────────────────────────────────────────────────

    /** 🛡 游客不写曝光记录（AC4：无跨会话记录，记了也没人读）。 */
    @Test
    void guestDoesNotWriteExposure() {
        store.markSeen(FeedRankCacheKey.forGuest("sess"), List.of(1L, 2L), NOW);
        verifyNoInteractions(redis);
    }

    /** 🛡 游客的衰减系数恒 1.0，且不发查询。 */
    @Test
    void guestGetsNoDecayAndIssuesNoQuery() {
        Map<Long, Double> f = store.decayFactors(FeedRankCacheKey.forGuest("sess"),
                List.of(1L, 2L), NOW);
        assertThat(f).containsOnly(Map.entry(1L, 1.0), Map.entry(2L, 1.0));
        verify(zset, never()).score(anyString(), any(Object[].class));
    }

    // ── 写路径 ──────────────────────────────────────────────────────

    @Test
    void markSeenAddsMembersPrunesAndSetsTtl() {
        store.markSeen(FeedRankCacheKey.forUser(5L), List.of(10L, 11L), NOW);

        verify(zset).add("feed:seen:u:5", "10", (double) NOW.toEpochMilli());
        verify(zset).add("feed:seen:u:5", "11", (double) NOW.toEpochMilli());
        verify(zset).removeRangeByScore(anyString(), anyDouble(), anyDouble());
        verify(redis).expire("feed:seen:u:5", Duration.ofDays(7));
    }

    @Test
    void emptyIdsIsNoop() {
        store.markSeen(FeedRankCacheKey.forUser(5L), List.of(), NOW);
        verifyNoInteractions(redis);
        assertThat(store.decayFactors(FeedRankCacheKey.forUser(5L), List.of(), NOW)).isEmpty();
    }

    // ── AC5：Redis 不可用 ───────────────────────────────────────────

    /** 🛡 读失败 → 全 1.0（不是 0、不抛错）。当作没看过，比白屏好得多。 */
    @Test
    void readFailureFallsBackToNoDecay() {
        when(zset.score(anyString(), any(Object[].class)))
                .thenThrow(new QueryTimeoutException("redis down"));

        Map<Long, Double> f = store.decayFactors(FeedRankCacheKey.forUser(1L), List.of(9L), NOW);

        assertThat(f).containsOnly(Map.entry(9L, 1.0));
    }

    /** 🛡 写失败也不得抛出 —— 首页不能因为记不上曝光就 500。 */
    @Test
    void writeFailureDoesNotThrow() {
        when(zset.add(anyString(), anyString(), anyDouble()))
                .thenThrow(new QueryTimeoutException("redis down"));

        store.markSeen(FeedRankCacheKey.forUser(1L), List.of(9L), NOW);
    }
}
