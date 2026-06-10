package com.petgo.triage.event;

import java.time.Instant;

/**
 * 分诊提交领域事件（Story 4.1，过去式，不可变 record）。经 {@code ApplicationEventPublisher} 进程内发布，
 * 供 triage 处理器 {@code @Async @TransactionalEventListener(AFTER_COMMIT)} 消费——保证任务已落库后再处理。
 *
 * <p>护栏：事件只带 {@code triageId}（不带症状/图片），避免健康数据在事件流中扩散。
 *
 * @param triageId  任务 id
 * @param createdAt 事件时间（UTC）
 */
public record TriageSubmittedEvent(long triageId, Instant createdAt) {
}
