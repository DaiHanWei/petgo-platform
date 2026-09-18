package com.tailtopia.place.service;

import com.tailtopia.admin.places.event.PlaceMergedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 后台合并场所之后，丢掉两个场所的 App 侧态度计数键（2026-09-18 场所表对齐 §6.1 第 5 条）。
 *
 * <p>合并时后台把被并方的评论整体改指保留场所 —— 这一步不经过 {@link PlaceAttitudeCounters} 的增减，
 * 保留场所的 👍/👎 在 Redis 里会少算被并方那部分，直到 TTL（10 分钟）到期。丢键 = 下次读立刻回库重算。
 * <p>事件本身在合并事务**提交之后**才到这里（AFTER_COMMIT），回滚的合并不会触发。
 */
@Component
public class PlaceMergedCounterListener {

    private final PlaceAttitudeCounters counters;

    public PlaceMergedCounterListener(PlaceAttitudeCounters counters) {
        this.counters = counters;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMerged(PlaceMergedEvent event) {
        counters.evictAfterCommit(event.keepPlaceId(), event.mergedPlaceId());
    }
}
