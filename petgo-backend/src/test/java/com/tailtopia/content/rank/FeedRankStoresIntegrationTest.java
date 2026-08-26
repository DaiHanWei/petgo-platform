package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * L1：曝光记录 + 序列快照走<b>真 Redis</b>往返（Story 16.1 · AC1/AC2/AC3/AC4）。
 *
 * <p>L0 那两个类用 mock 钉的是「调了什么命令」，这里钉的是「Redis 真这么理解」——
 * ZMSCORE 对未命中成员到底返不返 null、LRANGE 的区间是不是闭区间、TTL 有没有真设上。
 * 这些 mock 全都问不出来。
 *
 * <p>⚠️ userId 用 {@code SEQ} 取，跨次运行不撞键（Redis 不像 DB 有回滚，键会留在库里）。
 */
class FeedRankStoresIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private FeedSeenStore seen;

    @Autowired
    private FeedSequenceStore sequences;

    @Autowired
    private StringRedisTemplate redis;

    private long freshUserId() {
        return SEQ.incrementAndGet();
    }

    // ── AC1 / AC2：曝光记录 ─────────────────────────────────────────

    @Test
    void markedContentDecaysAndUnmarkedDoesNot() {
        FeedRankCacheKey key = FeedRankCacheKey.forUser(freshUserId());
        Instant now = Instant.now();

        seen.markSeen(key, List.of(1001L, 1002L), now);
        Map<Long, Double> f = seen.decayFactors(key, List.of(1001L, 1002L, 1003L), now);

        assertThat(f.get(1001L)).isEqualTo(0.3);
        assertThat(f.get(1002L)).isEqualTo(0.3);
        assertThat(f.get(1003L)).isEqualTo(1.0);

        // TTL 真设上了（否则曝光记录会永久占内存）
        Long ttl = redis.getExpire(FeedSeenStore.key(key));
        assertThat(ttl).isGreaterThan(0L);
    }

    /** 🔴 出窗的曝光不再降权 —— ZSET 方案的存在意义，真 Redis 上再钉一次。 */
    @Test
    void exposureOutsideWindowDoesNotDecayOnRealRedis() {
        FeedRankCacheKey key = FeedRankCacheKey.forUser(freshUserId());
        Instant now = Instant.now();

        seen.markSeen(key, List.of(2001L), now.minus(Duration.ofDays(30)));

        assertThat(seen.decayFactors(key, List.of(2001L), now).get(2001L)).isEqualTo(1.0);
    }

    /** 🛡 游客不写曝光记录：键压根不存在。 */
    @Test
    void guestLeavesNoExposureKey() {
        FeedRankCacheKey guest = FeedRankCacheKey.forGuest("sess-" + freshUserId());

        seen.markSeen(guest, List.of(3001L), Instant.now());

        assertThat(redis.hasKey(FeedSeenStore.key(guest))).isFalse();
        assertThat(seen.decayFactors(guest, List.of(3001L), Instant.now()).get(3001L))
                .isEqualTo(1.0);
    }

    // ── AC3：序列快照 ───────────────────────────────────────────────

    /** 🔴 同一种子翻页读到的是同一序列的不同段 —— 不重算、不重复、不跳过。 */
    @Test
    void sameSeedPagesThroughOneStableSequence() {
        FeedRankCacheKey key = FeedRankCacheKey.forUser(freshUserId());
        String seed = sequences.newSeed(Instant.now());
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 50).boxed().toList();

        sequences.append(key, seed, ids);

        List<Long> page1 = sequences.read(key, seed, 0, 20);
        List<Long> page2 = sequences.read(key, seed, 20, 20);
        List<Long> page3 = sequences.read(key, seed, 40, 20);

        assertThat(page1).hasSize(20).startsWith(1L).endsWith(20L);
        assertThat(page2).hasSize(20).startsWith(21L).endsWith(40L);
        assertThat(page3).hasSize(10).startsWith(41L).endsWith(50L);
        // 三页拼起来 = 原序列，无重复无遗漏
        assertThat(java.util.stream.Stream.of(page1, page2, page3).flatMap(List::stream).toList())
                .containsExactlyElementsOf(ids);
        // 再读一次第一页，结果不变（钉住「不重算」）
        assertThat(sequences.read(key, seed, 0, 20)).isEqualTo(page1);
        assertThat(sequences.length(key, seed)).isEqualTo(50L);
    }

    /** 下拉刷新 = 换种子 = 新序列（旧序列的内容读不到）。 */
    @Test
    void newSeedStartsAFreshSequence() {
        FeedRankCacheKey key = FeedRankCacheKey.forUser(freshUserId());
        Instant t = Instant.now();
        String seedA = sequences.newSeed(t);
        String seedB = sequences.newSeed(t.plusSeconds(1));

        sequences.append(key, seedA, List.of(11L, 12L));

        assertThat(sequences.read(key, seedA, 0, 20)).containsExactly(11L, 12L);
        assertThat(sequences.read(key, seedB, 0, 20)).isEmpty();
        assertThat(sequences.length(key, seedB)).isZero();
    }

    /** 游标超出已缓存长度 → 用同一种子续写下一段，续写后接得上。 */
    @Test
    void sameSeedCanBeExtendedInSegments() {
        FeedRankCacheKey key = FeedRankCacheKey.forUser(freshUserId());
        String seed = sequences.newSeed(Instant.now());

        sequences.append(key, seed, List.of(1L, 2L));
        assertThat(sequences.read(key, seed, 2, 20)).isEmpty(); // 还没续算

        sequences.append(key, seed, List.of(3L, 4L));

        assertThat(sequences.read(key, seed, 0, 20)).containsExactly(1L, 2L, 3L, 4L);
        assertThat(sequences.read(key, seed, 2, 20)).containsExactly(3L, 4L);
    }

    @Test
    void sequenceKeyCarriesTtl() {
        FeedRankCacheKey key = FeedRankCacheKey.forUser(freshUserId());
        String seed = sequences.newSeed(Instant.now());
        sequences.append(key, seed, List.of(1L));

        assertThat(redis.getExpire(FeedSequenceStore.key(key, seed))).isGreaterThan(0L);
    }
}
