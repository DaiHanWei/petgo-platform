package com.tailtopia.admin.places.repository;

import com.tailtopia.admin.places.domain.PlaceReport;
import com.tailtopia.admin.places.domain.PlaceReportStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code place_reports} 仓储（Story 5.1）。{@link #countByStatus} 供 B6 摘要条「待处理举报数」。 */
public interface PlaceReportRepository extends JpaRepository<PlaceReport, Long> {

    long countByStatus(PlaceReportStatus status);

    Optional<PlaceReport> findByPlaceIdAndReporterUserId(Long placeId, Long reporterUserId);

    List<PlaceReport> findByPlaceIdAndStatusOrderByCreatedAtAsc(Long placeId, PlaceReportStatus status);

    /** A1 场所举报右栏：该场所全部举报（Story 5.4）。 */
    List<PlaceReport> findByPlaceIdOrderByCreatedAtAsc(Long placeId);

    /**
     * 该场所全部 PENDING 举报一条 UPDATE 置终态（Story 5.4 AC4；驳回 → DISMISSED，下架 → ACTIONED），返回受影响行数。
     * 原子 + 幂等：并发双击 / 驳回与下架竞态只有一方改到行（复审 #7）。须在调用方事务内。
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE PlaceReport r SET r.status = :decision, r.handledBy = :adminId, r.handledAt = :now, r.updatedAt = :now"
            + " WHERE r.placeId = :placeId AND r.status = com.tailtopia.admin.places.domain.PlaceReportStatus.PENDING")
    int settlePending(@Param("placeId") long placeId, @Param("decision") PlaceReportStatus decision, @Param("adminId") long adminId,
            @Param("now") Instant now);
}
