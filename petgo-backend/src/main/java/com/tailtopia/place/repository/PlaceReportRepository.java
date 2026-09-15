package com.tailtopia.place.repository;

import com.tailtopia.place.domain.PlaceReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceReportRepository extends JpaRepository<PlaceReport, Long> {

    /**
     * 同一个人是否已举报过同一个场所（Story 1.5 · AC5）。
     *
     * <p>重复举报**幂等**（不报错、不再写一条）：连点五次不该在运营队列里变成五张工单。
     * 唯一索引 {@code uq_place_reports_reporter_place} 是最后一道防线，这里先查一次省掉
     * 大部分冲突异常。
     */
    boolean existsByPlaceIdAndReporterId(long placeId, long reporterId);
}
