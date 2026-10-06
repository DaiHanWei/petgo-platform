package com.tailtopia.admin.pin.dto;

import java.time.Instant;

/**
 * 内容选择器的一行候选（Story 11.1；bug 20260924-562 补作者与首图）。顶置与内容打标两处共用。
 *
 * @param authorId      作者 id（可空：极老数据）
 * @param authorName    作者昵称；未知 / 注销为 null
 * @param authorDeleted 作者已注销 —— 模板显示本地化「已注销用户」
 * @param thumbUrl      首图（公开 CDN 全 URL，与内容列表缩略图同源）；纯文字帖为 null
 */
public record PinnableContentRow(long id, String type, String summary, Instant createdAt,
        Long authorId, String authorName, boolean authorDeleted, String thumbUrl) {
}
