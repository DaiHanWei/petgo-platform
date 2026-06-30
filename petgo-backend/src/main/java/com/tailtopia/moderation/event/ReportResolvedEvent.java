package com.tailtopia.moderation.event;

import java.time.Instant;

/**
 * 举报已处理事件（Story 4.1，AB-3A）。无论下架/驳回均发布，供 notify 向**举报人**发统一模糊通知
 * （闭环反馈）。**刻意不携带处置结果/被举报内容/作者**——保证模糊（不透露结果、无申诉入口）。
 */
public record ReportResolvedEvent(long reportId, long reporterId, Instant resolvedAt) {
}
