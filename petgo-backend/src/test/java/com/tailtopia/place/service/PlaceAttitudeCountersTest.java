package com.tailtopia.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.place.domain.PlaceCommentAttitude;
import com.tailtopia.place.repository.PlaceCommentRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * L0（mock Redis + mock 仓储，无真 Redis / 无 DB）：推荐 / 不推荐计数器
 * （V1.3.0 batch-b1 Story 1.8 · AD-9）。
 *
 * <p>真跑自愈是 L1（要清真实 Redis key 再看它回算），这里守的是**行为形状**：
 * 批量读一次到底、缺值整对回算、减不到负、Redis 挂了不影响主动作。
 */
class PlaceAttitudeCountersTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private PlaceCommentRepository comments;
    private PlaceAttitudeCounters counters;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = Mockito.mock(StringRedisTemplate.class);
        ops = Mockito.mock(ValueOperations.class);
        comments = Mockito.mock(PlaceCommentRepository.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(comments.countVisibleAttitudesByPlaceIds(anyList())).thenReturn(List.of());
        counters = new PlaceAttitudeCounters(redis, comments);
    }

    // ===== AC6 批量读 =====

    /**
     * 🔴 一次 MGET 取回整页，**不逐个场所 GET**。
     *
     * <p>逐个查就是把 N+1 从数据库搬到了 Redis —— 延迟照样叠加，只是换了个地方发生。
     */
    @Test
    void readsTheWholePageInOneBatchCall() {
        when(ops.multiGet(anyList())).thenReturn(List.of("3", "9", "1", "0"));

        Map<Long, PlaceAttitudeCounters.Counts> result = counters.countsOf(List.of(10L, 20L));

        assertThat(result).hasSize(2);
        verify(ops).multiGet(anyList());
        verify(ops, never()).get(anyString());
    }

    @Test
    void mapsBatchValuesBackToTheRightPlaceAndSide() {
        // key 顺序：[rec:10, rec:20, notrec:10, notrec:20]
        when(ops.multiGet(anyList())).thenReturn(List.of("3", "9", "1", "0"));

        Map<Long, PlaceAttitudeCounters.Counts> result = counters.countsOf(List.of(10L, 20L));

        assertThat(result.get(10L)).isEqualTo(new PlaceAttitudeCounters.Counts(3, 1));
        assertThat(result.get(20L)).isEqualTo(new PlaceAttitudeCounters.Counts(9, 0));
    }

    @Test
    void emptyPageDoesNotTouchRedisAtAll() {
        assertThat(counters.countsOf(List.of())).isEmpty();
        verify(ops, never()).multiGet(anyList());
    }

    // ===== AC1 自愈 =====

    /** 🔴 缺值 → 回库重算 → 写回（AD-9：真值永远在表里，Redis 丢了只是慢一次）。 */
    @Test
    void missingKeysAreRecomputedFromTheDatabaseAndWrittenBack() {
        when(ops.multiGet(anyList())).thenReturn(Arrays.asList(null, null));
        when(comments.countVisibleAttitudesByPlaceIds(anyList())).thenReturn(rows(
                new Object[] {10L, PlaceCommentAttitude.RECOMMEND, 7L},
                new Object[] {10L, PlaceCommentAttitude.NOT_RECOMMEND, 2L}));

        Map<Long, PlaceAttitudeCounters.Counts> result = counters.countsOf(List.of(10L));

        assertThat(result.get(10L)).isEqualTo(new PlaceAttitudeCounters.Counts(7, 2));
        verify(ops).setIfAbsent("place:rec:10", "7", PlaceAttitudeCounters.TTL);
        verify(ops).setIfAbsent("place:notrec:10", "2", PlaceAttitudeCounters.TTL);
    }

    /** 🔴 两个键任意一个缺就**整对**重算 —— 只补一个会让 👍 是新的、👎 是旧的。 */
    @Test
    void halfMissingPairIsRecomputedAsAWhole() {
        when(ops.multiGet(anyList())).thenReturn(Arrays.asList("5", null));
        when(comments.countVisibleAttitudesByPlaceIds(anyList())).thenReturn(rows(
                new Object[] {10L, PlaceCommentAttitude.RECOMMEND, 7L}));

        assertThat(counters.countsOf(List.of(10L)).get(10L))
                .as("回算出来的 7 才是真值，Redis 里那个 5 是过期的")
                .isEqualTo(new PlaceAttitudeCounters.Counts(7, 0));
    }

    /** 值被写坏（非数字）→ 当缺失处理，走回算，而不是抛出去让页面 500。 */
    @Test
    void corruptValueFallsBackToRecompute() {
        when(ops.multiGet(anyList())).thenReturn(Arrays.asList("oops", "1"));

        assertThat(counters.countsOf(List.of(10L)).get(10L))
                .isEqualTo(PlaceAttitudeCounters.Counts.ZERO);
        verify(comments).countVisibleAttitudesByPlaceIds(anyList());
    }

    /** 🔴 Redis 整个挂了也不能让场所列表打不开：整批回落回算。 */
    @Test
    void redisFailureFallsBackToDatabaseInsteadOfBreakingTheList() {
        when(ops.multiGet(anyList())).thenThrow(new RuntimeException("redis down"));
        when(comments.countVisibleAttitudesByPlaceIds(anyList())).thenReturn(rows(
                new Object[] {10L, PlaceCommentAttitude.RECOMMEND, 4L}));

        assertThat(counters.countsOf(List.of(10L)).get(10L))
                .isEqualTo(new PlaceAttitudeCounters.Counts(4, 0));
    }

    /**
     * 🔴 **负值也当缺失处理**（不要洗成 0）。
     *
     * <p>键停在 -1（"减到负就置 0"那次补偿写失败了）时，洗成 0 会让它被当作健康值 ——
     * 之后每次 +1 都是 -1→0→1，这个场所的计数永远比库里低，且永远不会被回算修好。
     */
    @Test
    void negativeValueIsTreatedAsMissingAndRecomputed() {
        when(ops.multiGet(anyList())).thenReturn(Arrays.asList("-1", "2"));
        when(comments.countVisibleAttitudesByPlaceIds(anyList())).thenReturn(rows(
                new Object[] {10L, PlaceCommentAttitude.RECOMMEND, 6L}));

        assertThat(counters.countsOf(List.of(10L)).get(10L))
                .isEqualTo(new PlaceAttitudeCounters.Counts(6, 0));
        verify(comments).countVisibleAttitudesByPlaceIds(anyList());
    }

    /**
     * 🔴 回算写回用 **setIfAbsent**，不是无条件覆盖。
     *
     * <p>两个线程同时发现键缺失、各自回算，其中一个读到的是更新的库状态；
     * 无条件覆盖时慢的那个会把新值盖回旧值 —— 一次审核通过就这么被吞掉。
     */
    @Test
    void recomputeWriteBackNeverOverwritesAFresherValue() {
        when(ops.multiGet(anyList())).thenReturn(Arrays.asList(null, null));

        counters.countsOf(List.of(10L));

        verify(ops, never()).multiSet(org.mockito.ArgumentMatchers.anyMap());
        verify(ops).setIfAbsent("place:rec:10", "0", PlaceAttitudeCounters.TTL);
    }

    /** 🔴 每次写回都带 TTL —— 它是"丢一次增量也能自愈"的唯一保证（见类注释）。 */
    @Test
    void everyWriteBackCarriesTheTtl() {
        when(ops.multiGet(anyList())).thenReturn(Arrays.asList(null, null));

        counters.countsOf(List.of(10L));

        verify(ops, never()).set(anyString(), anyString());
        verify(ops, org.mockito.Mockito.times(2))
                .setIfAbsent(anyString(), anyString(), eq(PlaceAttitudeCounters.TTL));
    }

    // ===== AC5 三个触发点的增减 =====

    @Test
    void becomingVisibleIncrementsTheMatchingSide() {
        when(redis.hasKey(anyString())).thenReturn(true);

        counters.onCommentBecameVisible(10L, PlaceCommentAttitude.RECOMMEND);

        verify(ops).increment("place:rec:10", 1);
    }

    @Test
    void removalDecrementsTheMatchingSide() {
        when(redis.hasKey(anyString())).thenReturn(true);
        when(ops.increment(anyString(), eq(-1L))).thenReturn(2L);

        counters.onVisibleCommentRemoved(10L, PlaceCommentAttitude.NOT_RECOMMEND);

        verify(ops).increment("place:notrec:10", -1);
    }

    /** 未表态的评论不碰任何计数（两个数字都不含它）。 */
    @Test
    void commentsWithoutAnAttitudeTouchNoCounter() {
        counters.onCommentBecameVisible(10L, null);
        counters.onVisibleCommentRemoved(10L, null);

        verify(ops, never()).increment(anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    /** 减到负数就置 0（同既有未读角标的处理）。 */
    @Test
    void decrementNeverGoesBelowZero() {
        when(redis.hasKey(anyString())).thenReturn(true);
        when(ops.increment(anyString(), eq(-1L))).thenReturn(-1L);

        counters.onVisibleCommentRemoved(10L, PlaceCommentAttitude.RECOMMEND);

        verify(ops).set("place:rec:10", "0");
    }

    /**
     * 🔴 键不存在时**不要"从 0 起 incr"**：那个场所库里可能已经有 30 条评论
     * （Redis 被清 / 这个场所从没被列出来过）。先回算写回，不再额外加一次。
     */
    @Test
    void incrementOnAMissingKeyRecomputesInsteadOfStartingFromZero() {
        when(redis.hasKey(anyString())).thenReturn(false);
        when(comments.countVisibleAttitudesByPlaceIds(anyList())).thenReturn(rows(
                new Object[] {10L, PlaceCommentAttitude.RECOMMEND, 31L}));

        counters.onCommentBecameVisible(10L, PlaceCommentAttitude.RECOMMEND);

        verify(ops, never()).increment(anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(ops).setIfAbsent("place:rec:10", "31", PlaceAttitudeCounters.TTL);
    }

    /**
     * 🔴 Redis 写失败**不能把主动作带崩**：计数是派生数据，掉一次增量下次读就自愈了，
     * 而"发表评论"失败是用户看得见的。
     */
    @Test
    void redisWriteFailureDoesNotPropagate() {
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));

        counters.onCommentBecameVisible(10L, PlaceCommentAttitude.RECOMMEND); // 不抛即通过
    }

    /**
     * 🔴 有活动事务时，计数变更必须推迟到**提交之后**（code-review 2026-09-15）。
     *
     * <p>事务内直接 INCR 的话，那个事务一旦回滚（删除时撞死锁、约束冲突、任何异常），
     * 库里什么都没变、计数却已经改了；用户重试成功 → 同一条评论减了两次。
     */
    @Test
    void counterChangesAreDeferredUntilAfterCommit() {
        when(redis.hasKey(anyString())).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            counters.onCommentBecameVisible(10L, PlaceCommentAttitude.RECOMMEND);
            verify(ops, never()).increment(anyString(), org.mockito.ArgumentMatchers.anyLong());

            // 事务提交 → 此刻才真的写 Redis。
            TransactionSynchronizationUtils.triggerAfterCommit();
            verify(ops).increment("place:rec:10", 1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 事务回滚 → 计数**一点都没动**（afterCommit 不会被触发）。 */
    @Test
    void rolledBackTransactionLeavesTheCounterUntouched() {
        when(redis.hasKey(anyString())).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            counters.onVisibleCommentRemoved(10L, PlaceCommentAttitude.RECOMMEND);
            // 回滚：不触发 afterCommit。
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(ops, never()).increment(anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    private static List<Object[]> rows(Object[]... rows) {
        return new ArrayList<>(Arrays.asList(rows));
    }
}
