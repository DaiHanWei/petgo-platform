package com.tailtopia.shop.order.notify;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 新订单提醒待发队列仓储（Story 3-4）。 */
public interface ShopOrderNotifyQueueRepository
        extends JpaRepository<ShopOrderNotifyQueueEntry, Long> {

    boolean existsByShopOrderId(long shopOrderId);

    /**
     * 取待发行（按登记时刻升序），上界由调用方给。
     *
     * <p>🔴 <b>升序是语义的一部分，不是排版偏好</b>：
     * {@code ShopOrderNotifyService.collectWindow()} 拿第一行的 {@code created_at} 当窗口左端，
     * 并在遇到第一个越界行时直接 {@code break} —— 顺序一乱，窗口切分就错，
     * 「跨窗口分两条」（AC3）会变成随机分组。
     */
    List<ShopOrderNotifyQueueEntry> findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            ShopOrderNotifyQueueEntry.Status status, Instant createdAtBefore, Pageable pageable);

    /**
     * 取冷却期已过的 {@code FAILED} 行，放回队列重试（复审 #15）。
     *
     * <p>按 {@code updated_at} 而不是 {@code created_at} 卡 —— 要等的是「离上次失败多久」，
     * 不是「这单下了多久」。
     */
    List<ShopOrderNotifyQueueEntry> findByStatusAndUpdatedAtBefore(
            ShopOrderNotifyQueueEntry.Status status, Instant updatedAtBefore, Pageable pageable);
}
