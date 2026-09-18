package com.tailtopia.admin.places.dto;

/** B6 摘要条四格（Story 5.2 AC3，随筛选联动，单条 {@code COUNT(*) FILTER} 聚合）。 */
public record PlaceSummary(long activeCount, long todayNew, long pendingReports, long checkins) {
}
