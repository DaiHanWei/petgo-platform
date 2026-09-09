package com.tailtopia.admin.dashboard.repository;

import com.tailtopia.admin.dashboard.domain.OpsDailyMetric;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** {@code ops_daily_metrics} 仓储（V1.3.0 Story 3.2 AC1）。写入归 3-3 跑批；3-4 看板按日期区间取数。 */
public interface OpsDailyMetricRepository extends JpaRepository<OpsDailyMetric, Long> {

    boolean existsByReportDate(LocalDate reportDate);

    List<OpsDailyMetric> findByReportDateBetweenOrderByReportDate(LocalDate from, LocalDate to);

    @Query("select distinct m.reportDate from OpsDailyMetric m order by m.reportDate")
    List<LocalDate> findDistinctReportDates();
}
