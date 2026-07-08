package com.tailtopia.moderation.repository;

import com.tailtopia.moderation.domain.ContentReport;
import com.tailtopia.moderation.domain.ReportReason;
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

    /** 某帖指定状态的举报单：内容下架时把该帖 PENDING 举报单批量置 RESOLVED 用（bug 20260630-155）。 */
    List<ContentReport> findByPostIdAndStatus(long postId, ReportStatus status);

    /** 某帖的举报次数（队列详情展示）。 */
    long countByPostId(long postId);

    /**
     * 某帖是否存在指定原因的举报（内容审核 cm-6 §5.1）：判 P0「法律违规」——是否含 {@code ILLEGAL} 原因举报
     * （本次或历史，同事务内本次刚写入亦可见）。
     */
    boolean existsByPostIdAndReasonType(long postId, ReportReason reasonType);
}
