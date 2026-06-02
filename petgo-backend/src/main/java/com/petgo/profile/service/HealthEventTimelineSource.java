package com.petgo.profile.service;

import java.time.Instant;
import java.util.List;

/**
 * 健康事件时间线数据源端口（Story 2.4 定义，Story 2.5 实现）。
 *
 * <p>profile 时间线聚合经此接口取健康事件，**不直接读健康事件表**（架构 Architectural Boundaries）。
 * Story 2.4 期无实现 bean → 聚合对空健康源稳健（返回空段）；Story 2.5 提供真实 bean。
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
}
