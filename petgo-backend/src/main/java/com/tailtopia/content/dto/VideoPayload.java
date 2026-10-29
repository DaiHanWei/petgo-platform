package com.tailtopia.content.dto;

import jakarta.validation.constraints.Size;

/**
 * 视频帖请求里的视频信息（专题线 video Story 1.3 · AD-8）。封面放在 {@code imageUrls[0]}。
 *
 * <p>时长、宽高由客户端读 MP4 文件头上报（宽高按<b>显示方向</b>）；服务端只做范围校验，
 * 实际大小以发布时 HEAD 为准。权威校验在 {@code VideoPostValidator}。
 *
 * @param objectKey  1.2 视频上传策略返回的对象 key
 * @param durationMs 时长（毫秒）
 * @param width      显示宽
 * @param height     显示高
 */
public record VideoPayload(
        @Size(max = 256, message = "视频标识无效") String objectKey,
        Integer durationMs,
        Integer width,
        Integer height) {
}
