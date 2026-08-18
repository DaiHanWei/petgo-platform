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
 * <p>🔴 <b>健康事件按「就诊日期」定位</b>（V1.1.6 修正）。
 * 此前本注释写着「健康事件无独立 event_date 概念；问诊即记」—— <b>那是错的</b>：
 * 表里一直有 NOT NULL 的 {@code event_date}，而 {@code created_at} 是<b>归档进档案的时刻</b>。
 * 一次三个月前的问诊今天才归档，两者能差三个月，按后者定位就会把它显示在「今天」。
 */
public interface HealthEventTimelineSource {

    /** 健康事件时间线视图（剥离症状细节，仅摘要 + AI 评级 + 来源，日志不落明文）。
     * {@code sourceType} = {@link com.tailtopia.profile.domain.HealthSourceType} 枚举名（AI_TRIAGE/VET_CONSULT），
     * 供前端区分 AI 分诊 vs 兽医问诊条目（bug 20260702-231：此前丢失 → 兽医问诊被误显为「AI Consultation」）。 */
    record HealthEventView(Instant createdAt, LocalDate eventDate, String aiLevel,
            String symptomSummary, String sourceType, String sourceRef) {
    }

    /**
     * 取某用户在时间线锚点<b>之前</b>的健康事件（就诊日期倒序 → 同日归档时刻倒序）。
     *
     * <p>🔴 <b>锚点是复合键，不能退化成单键上界。</b>排序按就诊日期、取数按归档时刻
     * 就是「两把尺子」：补录的旧问诊在排序上落到旧位置、在翻页上被当成新记录，
     * 跨页时丢失或重复。成长内容早先正是踩了这个坑才做的锚点重构。
     *
     * @param anchorDate 锚点的事件日期
     * @param anchorKey  锚点的同日排序键
     * @param limit      本批最多条数
     */
    List<HealthEventView> recentHealthEvents(long ownerId, LocalDate anchorDate, Instant anchorKey,
            int limit);

    /**
     * 取某用户<b>就诊日期</b>落在 [from, to] 内的健康事件（日历月角标）。
     *
     * <p>🔴 参数从时刻区间改成了<b>日期区间</b>（V1.1.6 修正）—— 改之前查的是归档时刻，
     * 于是补录的旧问诊会落到「今天」那一格。
     *
     * <p>默认空实现：无实现 bean 时退化为空。
     */
    default List<HealthEventView> healthEventsInRange(long ownerId, LocalDate from, LocalDate to) {
        return List.of();
    }

    /** 取某用户<b>就诊日期</b>为某天的健康事件（当天详情）。同上，按就诊日期而非归档时刻。 */
    default List<HealthEventView> healthEventsOnDay(long ownerId, LocalDate day) {
        return List.of();
    }

    /** 某用户问诊（健康事件）总数（Story 2.4 AC5 统计栏「问诊 X 次」）。默认 0。 */
    default long countHealthEvents(long ownerId) {
        return 0L;
    }
}
