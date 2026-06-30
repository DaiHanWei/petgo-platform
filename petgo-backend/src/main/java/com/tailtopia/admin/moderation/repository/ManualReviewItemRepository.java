package com.tailtopia.admin.moderation.repository;

import com.tailtopia.admin.moderation.domain.ManualReviewItem;
import com.tailtopia.admin.moderation.domain.ReviewStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 人工审核队列仓储（Story 4.3）。 */
public interface ManualReviewItemRepository extends JpaRepository<ManualReviewItem, Long> {

    /** 某状态的队列项，按提交时间升序（队列页 PENDING 列表 / 24h 高亮）。 */
    List<ManualReviewItem> findByStatusOrderBySubmittedAtAsc(ReviewStatus status);

    /** 某状态且提交早于阈值的项（超时扫描：PENDING + submittedAt < cutoff）。 */
    List<ManualReviewItem> findByStatusAndSubmittedAtBefore(ReviewStatus status, Instant cutoff);
}
