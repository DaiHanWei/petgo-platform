package com.petgo.profile.dto;

import java.time.Instant;
import java.util.List;

/**
 * 成长时间线条目（Story 2.4）。两类合并倒序：
 * <ul>
 *   <li>{@code HAPPY_MOMENT}：date + imageUrls + text。</li>
 *   <li>{@code HEALTH_EVENT}：date + aiLevel + symptomSummary（健康数据，日志不落明文）。</li>
 * </ul>
 * Jackson NON_NULL：非本类字段省略。时间 ISO-8601 UTC。
 */
public record TimelineItemResponse(
        String kind,
        Instant date,
        Long postId,
        List<String> imageUrls,
        String text,
        String aiLevel,
        String symptomSummary) {

    public static final String HAPPY_MOMENT = "HAPPY_MOMENT";
    public static final String HEALTH_EVENT = "HEALTH_EVENT";

    public static TimelineItemResponse happyMoment(Long postId, Instant date, List<String> imageUrls,
            String text) {
        return new TimelineItemResponse(HAPPY_MOMENT, date, postId, imageUrls, text, null, null);
    }

    public static TimelineItemResponse healthEvent(Instant date, String aiLevel, String symptomSummary) {
        return new TimelineItemResponse(HEALTH_EVENT, date, null, null, null, aiLevel, symptomSummary);
    }
}
