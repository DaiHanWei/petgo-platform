package com.tailtopia.content.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 成长日历「快乐时刻」时间线视图（Story 2.4）。content 模块对外暴露的只读投影，
 * 供 profile 时间线聚合**经 service 接口**取数（不暴露实体、不让 profile join content 表）。
 *
 * <p>{@code eventDate}（F9，Story 2.3 加列）决定档案侧时间线/日历显示位置；{@code createdAt} 决定同日内
 * 排序与 Feed 排序（两者解耦）。
 */
public record GrowthMomentView(
        Long id,
        Instant createdAt,
        LocalDate eventDate,
        List<String> imageUrls,
        String text) {

    /** 首图（无图返回 null），供日历格子背景缩略图取用。 */
    public String firstImageUrl() {
        return (imageUrls == null || imageUrls.isEmpty()) ? null : imageUrls.get(0);
    }
}
