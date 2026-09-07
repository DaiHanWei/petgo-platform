package com.tailtopia.notify.dto;

import java.util.List;

/**
 * 通知中心游标分页（Story 6.6）。架构格式 {@code {items, nextCursor, hasMore}}。
 *
 * <p>🔴 {@code nextCursor} 对客户端是<b>不透明串</b>（{@code com.tailtopia.shared.paging.KeysetCursor}
 * 的 base64url 编码）—— 原样回传即可，<b>不要解析、不要自己拼</b>。
 * 2026-08-18 它已经从「纯 epochMillis」变过一次：只有 {@code created_at} 的游标会整批跳过
 * 同刻记录（{@code action_items: NOTIFY-CURSOR-TIE}）。以后还可能再变。
 */
public record NotificationPage(List<NotificationItem> items, String nextCursor, boolean hasMore) {
}
