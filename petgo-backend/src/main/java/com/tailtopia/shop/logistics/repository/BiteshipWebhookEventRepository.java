package com.tailtopia.shop.logistics.repository;

import com.tailtopia.shop.logistics.domain.BiteshipWebhookEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Biteship Webhook 收件表仓储（v1.4.2 shop Story 5.1）。 */
public interface BiteshipWebhookEventRepository extends JpaRepository<BiteshipWebhookEvent, Long> {

    /** 重试候选：未处理、未告警、收到超过 1 分钟（避开刚收到、异步处理尚在途的行），按收到时刻。 */
    @Query("""
            SELECT e FROM BiteshipWebhookEvent e
            WHERE e.processedAt IS NULL AND e.alertedAt IS NULL AND e.receivedAt < :before
            ORDER BY e.receivedAt ASC, e.id ASC
            """)
    List<BiteshipWebhookEvent> findRetryable(@Param("before") Instant before, Pageable pageable);

    /** 清理已处理超过 30 天的行（每轮最多 {@code limit} 行）。 */
    @Modifying
    @Query(value = """
            DELETE FROM biteship_webhook_events WHERE id IN (
                SELECT id FROM biteship_webhook_events WHERE processed_at < :before ORDER BY id LIMIT :limit)
            """, nativeQuery = true)
    int deleteProcessedBefore(@Param("before") Instant before, @Param("limit") int limit);
}
