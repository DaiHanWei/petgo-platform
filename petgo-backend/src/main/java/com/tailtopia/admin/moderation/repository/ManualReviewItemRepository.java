package com.tailtopia.admin.moderation.repository;

import com.tailtopia.admin.moderation.domain.ManualReviewItem;
import com.tailtopia.admin.moderation.domain.ReviewStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 人工审核队列仓储（Story 4.3）。 */
public interface ManualReviewItemRepository extends JpaRepository<ManualReviewItem, Long> {

    /** 某状态的队列项，按提交时间升序（超时扫描/兜底；story 8 前的队列页排序）。 */
    List<ManualReviewItem> findByStatusOrderBySubmittedAtAsc(ReviewStatus status);

    /**
     * 某状态的队列项，按优先级升序 + 同级提交时间升序（story 8 §5.1 队列页排序）。
     * {@code P0<P1<P2} 字典序即优先级序；复合索引 idx_manual_review_queue_pending_order 支撑。
     */
    List<ManualReviewItem> findByStatusOrderByPriorityAscSubmittedAtAsc(ReviewStatus status);

    /** 某状态且提交早于阈值的项（超时扫描：PENDING + submittedAt < cutoff）。 */
    List<ManualReviewItem> findByStatusAndSubmittedAtBefore(ReviewStatus status, Instant cutoff);

    /**
     * 注销联动（story 9，§5.5.2）：把注销用户内容对应的 PENDING 队列条目置 TIMED_OUT 终态（移出队列，不再发布）。
     * 队列表无作者列，按 content_id 子查询映射到作者：CONTENT_POST → content_posts.author_id、
     * COMMENT → comments.author_id。仅动 PENDING（幂等）。返回移除条数。
     */
    @Modifying
    @Query(value = """
            UPDATE manual_review_queue q
               SET status = 'TIMED_OUT', decided_at = now(), updated_at = now()
             WHERE q.status = 'PENDING'
               AND (
                    (q.content_type = 'CONTENT_POST'
                       AND q.content_id IN (SELECT id FROM content_posts WHERE author_id = :authorId))
                 OR (q.content_type = 'COMMENT'
                       AND q.content_id IN (SELECT id FROM comments WHERE author_id = :authorId))
               )
            """, nativeQuery = true)
    int deactivatePendingByAuthor(@Param("authorId") long authorId);
}
