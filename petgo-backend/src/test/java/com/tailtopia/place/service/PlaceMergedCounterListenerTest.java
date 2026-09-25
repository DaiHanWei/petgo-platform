package com.tailtopia.place.service;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tailtopia.place.repository.PlaceCommentRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * L0：场所合并后丢计数键（2026-09-25 code review #7）。
 *
 * <p>监听器跑在 AFTER_COMMIT 阶段，此时同步机制<b>仍处于激活态</b>：再注册提交后回调不会被执行，
 * 删除被静默跳过。这里用 initSynchronization 复现那个状态。
 */
class PlaceMergedCounterListenerTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final PlaceAttitudeCounters counters = new PlaceAttitudeCounters(redis, mock(PlaceCommentRepository.class));

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("🔴 同步机制激活（AFTER_COMMIT 里的真实状态）时，合并监听器仍当场删掉两个场所的计数键")
    void mergeListenerEvictsImmediatelyEvenWithActiveSynchronization() {
        TransactionSynchronizationManager.initSynchronization();

        new PlaceMergedCounterListener(counters).onMerged(new com.tailtopia.admin.places.event.PlaceMergedEvent(10L, 20L, java.time.Instant.now(), 1L));

        verify(redis).delete(List.of("place:rec:10", "place:notrec:10", "place:rec:20", "place:notrec:20"));
    }

    @Test
    @DisplayName("对照：同样状态下 evictAfterCommit 只挂回调、不会当场删 —— 这正是原 bug")
    void evictAfterCommitDefersWhileSynchronizationActive() {
        TransactionSynchronizationManager.initSynchronization();

        counters.evictAfterCommit(10L);

        verify(redis, never()).delete(anyList());
    }
}
