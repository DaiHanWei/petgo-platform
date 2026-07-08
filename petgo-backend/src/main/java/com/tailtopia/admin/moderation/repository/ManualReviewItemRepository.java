package com.tailtopia.admin.moderation.repository;

import com.tailtopia.admin.moderation.domain.ManualReviewItem;
import com.tailtopia.admin.moderation.domain.ReviewStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
