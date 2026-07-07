package com.tailtopia.profile.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 成长时间线条目（Story 2.4）。两类合并：
 * <ul>
 *   <li>{@code HAPPY_MOMENT}：date(createdAt) + eventDate(F9 显示/排序位置) + imageUrls + text。</li>
 *   <li>{@code HEALTH_EVENT}：date(createdAt) + aiLevel + symptomSummary（健康数据，日志不落明文）。</li>
 * </ul>
 * 排序：时间线按 {@code eventDate}（快乐时刻）/{@code date}（健康事件）倒序；当天详情按 {@code date}(createdAt) 正序。
 * {@code date} 兼作游标。Jackson NON_NULL：非本类字段省略。时间 ISO-8601 UTC / 日期 ISO LocalDate。
 */
public record TimelineItemResponse(
        String kind,
        Instant date,
        LocalDate eventDate,
        Long postId,
        List<String> imageUrls,
        String text,
        String aiLevel,
        String symptomSummary,
        String sourceType,
        String sourceRef) {

    public static final String HAPPY_MOMENT = "HAPPY_MOMENT";
    public static final String HEALTH_EVENT = "HEALTH_EVENT";

    public static TimelineItemResponse happyMoment(Long postId, Instant date, LocalDate eventDate,
            List<String> imageUrls, String text) {
        return new TimelineItemResponse(HAPPY_MOMENT, date, eventDate, postId, imageUrls, text, null, null, null, null);
    }

    /**
     * 健康事件条目。{@code sourceType} = AI_TRIAGE / VET_CONSULT，前端据此区分 AI/兽医（bug 20260702-231）。
     * {@code sourceRef} = 问诊/会话 token（幂等键），前端据此深链到对应结果页（bug 20260706-259）。
     */
    public static TimelineItemResponse healthEvent(Instant date, String aiLevel, String symptomSummary,
            String sourceType, String sourceRef) {
        return new TimelineItemResponse(HEALTH_EVENT, date, null, null, null, null, aiLevel, symptomSummary,
                sourceType, sourceRef);
    }

    /** 排序/显示有效日期（快乐时刻取 eventDate，缺省回退 date 的 UTC 日；健康事件取 date 的 UTC 日）。 */
    public LocalDate effectiveDate() {
        if (eventDate != null) {
            return eventDate;
        }
        return date.atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }
}
