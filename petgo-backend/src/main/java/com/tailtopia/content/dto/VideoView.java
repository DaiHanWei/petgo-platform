package com.tailtopia.content.dto;

/**
 * 视频帖的视频信息（专题线 video Story 2.1 · AD-8）：只在视频帖下发（NON_NULL）。
 *
 * <p>App 判断视频帖一律看 {@code mediaType}，<b>不要</b>用 {@code video != null} 判断（架构「格式」节）。
 *
 * @param url        视频播放地址：公开时为视频分发地址（CDN 或 OSS 源站），作者看未公开的帖为签名 URL
 * @param durationMs 时长（毫秒）
 * @param width      显示宽（按显示方向）
 * @param height     显示高（按显示方向）
 */
public record VideoView(String url, Integer durationMs, Integer width, Integer height) {
}
