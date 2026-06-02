package com.petgo.content.service;

import java.time.Instant;
import java.util.List;

/**
 * 成长日历「快乐时刻」时间线视图（Story 2.4）。content 模块对外暴露的只读投影，
 * 供 profile 时间线聚合**经 service 接口**取数（不暴露实体、不让 profile join content 表）。
 */
public record GrowthMomentView(
        Long id,
        Instant createdAt,
        List<String> imageUrls,
        String text) {
}
