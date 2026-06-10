package com.tailtopia.profile.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 健康事件时间线数据源端口（Story 2.4 定义，Story 2.5 实现）。
 *
 * <p>profile 时间线聚合经此接口取健康事件，**不直接读健康事件表**（架构 Architectural Boundaries）。
 * Story 2.4 期无实现 bean → 聚合对空健康源稳健（返回空段/0）；Story 2.5 提供真实 bean。
 *
 * <p>健康事件以发生时刻 {@code createdAt} 定位（健康事件无独立 event_date 概念；问诊即记）。
 */
public interface HealthEventTimelineSource {

    /** 健康事件时间线视图（剥离症状细节，仅摘要 + AI 评级，日志不落明文）。 */
    record HealthEventView(Instant createdAt, String aiLevel, String symptomSummary) {
    }

    /**
     * 取某用户的健康事件，createdAt 倒序游标分页。
     *
     * @param before 仅取该时刻之前的（null = 从最新开始）
     * @param limit  本批最多条数
     */
    List<HealthEventView> recentHealthEvents(long ownerId, Instant before, int limit);

    /**
     * 取某用户在 [from, to)（UTC 时刻区间，对应日历月）内的健康事件（Story 2.4 R2 日历角标）。
     * 默认空实现——2.4 期可能无 bean / 2.5 未覆盖时退化为空。
     */
    default List<HealthEventView> healthEventsInRange(long ownerId, Instant from, Instant to) {
        return List.of();
    }

    /**
     * 取某用户在某天（[dayStart, dayEnd) UTC）的健康事件（Story 2.4 R2 当天详情），created_at 升序由调用方排序。
     */
    default List<HealthEventView> healthEventsOnDay(long ownerId, Instant dayStart, Instant dayEnd) {
        return List.of();
    }

    /** 某用户问诊（健康事件）总数（Story 2.4 AC5 统计栏「问诊 X 次」）。默认 0。 */
    default long countHealthEvents(long ownerId) {
        return 0L;
    }
}
