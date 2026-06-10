package com.tailtopia.moderation.repository;

import com.tailtopia.moderation.domain.ContentReport;
import com.tailtopia.moderation.domain.ReportStatus;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 举报工单读写（Story 3.7）。提交（moderation）+ Admin 队列读取/处理。
 */
public interface ContentReportRepository extends JpaRepository<ContentReport, Long> {

    boolean existsByPostIdAndReporterId(long postId, long reporterId);

    /** Admin 队列：按状态列举（PENDING 优先），created_at 倒序分页。 */
    List<ContentReport> findByStatusOrderByCreatedAtDesc(ReportStatus status, Pageable pageable);

    /** 某帖的举报次数（队列详情展示）。 */
    long countByPostId(long postId);
}
