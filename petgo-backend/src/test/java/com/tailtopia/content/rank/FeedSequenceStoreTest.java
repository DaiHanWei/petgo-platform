package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/** L0：序列快照（Story 16.1 · AC3 / AC5）。 */
class FeedSequenceStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-24T10:00:00Z");

    private StringRedisTemplate redis;
    private ListOperations<String, String> list;
    private FeedSequenceStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        list = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(list);
        store = new FeedSequenceStore(redis, new FeedRankProperties(30, 100, 1000));
    }

    // ── 种子 ────────────────────────────────────────────────────────

    @Test
    void seedIsStableForSameRefreshAndDiffersAcrossRefreshes() {
        assertThat(store.newSeed(NOW)).isEqualTo(store.newSeed(NOW));
        assertThat(store.newSeed(NOW)).isNotEqualTo(store.newSeed(NOW.plusMillis(1)));
    }

    /** ⚠️ 键空间已带 namespace，种子里不再拼 userId（拼了等于写两遍）。 */
    @Test
    void seedDoesNotEmbedUserId() {
        assertThat(store.newSeed(NOW)).doesNotContain("u:");
    }

    // ── AC3：同一种子读到同一序列，不重算 ───────────────────────────

    @Test
    void readReturnsCachedSliceForGivenOffset() {
        FeedRankCacheKey key = FeedRankCacheKey.forUser(1L);
        when(list.range("feed:seq:u:1:seed1", 20L, 39L))
                .thenReturn(List.of("101", "102", "103"));

        assertThat(store.read(key, "seed1", 20, 20)).containsExactly(101L, 102L, 103L);
    }

    /** 换种子 = 换键 = 重新生成序列（读不到旧内容）。 */
    @Test
    void differentSeedIsADifferentKey() {
        FeedRankCacheKey key = FeedRankCacheKey.forUser(1L);
        when(list.range("feed:seq:u:1:seedA", 0L, 19L)).thenReturn(List.of("1", "2"));
        when(list.range("feed:seq:u:1:seedB", 0L, 19L)).thenReturn(List.of());

        assertThat(store.read(key, "seedA", 0, 20)).containsExactly(1L, 2L);
        assertThat(store.read(key, "seedB", 0, 20)).isEmpty();
    }

    @Test
    void appendPushesAllAndRefreshesTtl() {
        store.append(FeedRankCacheKey.forUser(2L), "s", List.of(7L, 8L));

        verify(list).rightPushAll("feed:seq:u:2:s", List.of("7", "8"));
        verify(redis).expire("feed:seq:u:2:s", Duration.ofMinutes(30));
    }

    @Test
    void lengthReportsCachedSize() {
        when(list.size("feed:seq:u:3:s")).thenReturn(42L);
        assertThat(store.length(FeedRankCacheKey.forUser(3L), "s")).isEqualTo(42L);
    }

    // ── 游客照常使用序列快照（只有曝光衰减对游客不生效） ─────────────

    @Test
    void guestStillUsesSequenceSnapshot() {
        FeedRankCacheKey guest = FeedRankCacheKey.forGuest("sess");
        store.append(guest, "s", List.of(1L));
        verify(list).rightPushAll("feed:seq:a:sess:s", List.of("1"));
    }

    // ── AC5：Redis 不可用 ───────────────────────────────────────────

    /** 🛡 读失败 → 空列表（调用方据此实时算当页），不抛错。 */
    @Test
    void readFailureReturnsEmptyInsteadOfThrowing() {
        when(list.range(anyString(), anyLong(), anyLong()))
                .thenThrow(new QueryTimeoutException("redis down"));

        assertThat(store.read(FeedRankCacheKey.forUser(1L), "s", 0, 20)).isEmpty();
    }

    @Test
    void appendFailureDoesNotThrow() {
        when(list.rightPushAll(anyString(), any(List.class)))
                .thenThrow(new QueryTimeoutException("redis down"));

        store.append(FeedRankCacheKey.forUser(1L), "s", List.of(1L));
    }

    @Test
    void lengthFailureReportsZero() {
        when(list.size(anyString())).thenThrow(new QueryTimeoutException("redis down"));
        assertThat(store.length(FeedRankCacheKey.forUser(1L), "s")).isZero();
    }

    // ── 边界 ────────────────────────────────────────────────────────

    @Test
    void emptyAppendAndBadRangeAreNoops() {
        store.append(FeedRankCacheKey.forUser(1L), "s", List.of());
        assertThat(store.read(FeedRankCacheKey.forUser(1L), "s", 0, 0)).isEmpty();
        assertThat(store.read(FeedRankCacheKey.forUser(1L), "s", -1, 20)).isEmpty();
        verifyNoInteractions(list);
    }
}
