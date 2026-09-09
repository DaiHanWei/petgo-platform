package com.tailtopia.admin.places.event;

import java.time.Instant;

/**
 * 场所合并事件（V1.3.0 Story 5.3 AC4，契约 X-1）：{@code mergedPlaceId} 已并入 {@code keepPlaceId}，子表（照片 / 评论 / 打卡）已改指保留场所。
 * 在合并事务内发布（Spring 事件）；<b>本分支不写监听器</b>——App 分支按契约 X-1 用 {@code @TransactionalEventListener(AFTER_COMMIT)}
 * 监听并把护照章归并（同一用户在 A、B 都盖过 → 并一枚、次数相加），监听方法须 {@code REQUIRES_NEW}（AFTER_COMMIT 阶段默认 REQUIRED 会吞写）。
 */
public record PlaceMergedEvent(long keepPlaceId, long mergedPlaceId, Instant mergedAt, long actorAdminAccountId) {
}
