package com.tailtopia.notify.dto;

import java.util.List;

/** 通知中心游标分页（Story 6.6）。架构格式 {@code {items, nextCursor, hasMore}}（nextCursor=末条 epochMillis）。 */
public record NotificationPage(List<NotificationItem> items, String nextCursor, boolean hasMore) {
}
