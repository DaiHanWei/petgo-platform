package com.tailtopia.growth.web.dto;

/**
 * {@code PUT /api/v1/me/growth/frame} 请求体（V1.4.0 ① Story 3.3）。{@code frame} 收字符串、由控制器解析：
 * 缺失 / 不在 {@code NONE / BASIC / UPGRADED / ANIMATED} 内 → 422 {@code validation}。
 */
public record GrowthFrameRequest(String frame) {
}
