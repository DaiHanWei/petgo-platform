package com.tailtopia.admin.places.repository;

import com.tailtopia.admin.places.domain.PlaceReport;
import com.tailtopia.admin.places.domain.PlaceReportStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code place_reports} 仓储（Story 5.1）。{@link #countByStatus} 供 B6 摘要条「待处理举报数」。 */
public interface PlaceReportRepository extends JpaRepository<PlaceReport, Long> {

    long countByStatus(PlaceReportStatus status);

    Optional<PlaceReport> findByPlaceIdAndReporterUserId(Long placeId, Long reporterUserId);

    List<PlaceReport> findByPlaceIdAndStatusOrderByCreatedAtAsc(Long placeId, PlaceReportStatus status);
}
