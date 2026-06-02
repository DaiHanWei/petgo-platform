package com.petgo.consult.dto;

/**
 * 兽医咨询可用性（Story 5.2 建，Story 5.3 扩展）。
 *
 * <p>护栏：<b>只回 {@code vetOnline} bool，绝不回精确在线人数</b>（架构 FR-4B：概率性展示、不暴露实时人数）。
 * {@code expectedWindow} 为离线态「预期恢复时段」的**配置文案 key**（前端映射 l10n），非实时计算。
 */
public record ConsultAvailabilityResponse(boolean vetOnline, String expectedWindow) {

    /** 恢复时段配置文案 key（前端映射「工作日 8:00–23:00 通常有兽医在线」类静态文案）。 */
    public static final String DEFAULT_WINDOW_KEY = "WEEKDAY_8_23";
}
