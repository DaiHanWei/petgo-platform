package com.tailtopia.profile.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 当天详情（Story 2.4 R2 · F9）。某 {@code event_date} 当天的快乐时刻 + 健康事件，
 * 按 {@code created_at} **正序**排列。前端当天详情页**不设「+」、不设删除入口**（AC6）。
 *
 * @param date  当天日期
 * @param items 当日条目（created_at 正序）
 */
public record DayDetailResponse(LocalDate date, List<TimelineItemResponse> items) {
}
