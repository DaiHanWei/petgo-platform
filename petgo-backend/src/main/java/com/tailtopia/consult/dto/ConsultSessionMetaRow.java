package com.tailtopia.consult.dto;

import java.time.Instant;

/**
 * 后台问诊会话元数据行（Story 5.2，AB-4B）。**仅 TailTopia 系统内元数据 + 评分**——
 * 绝不含 IM 正文 / AI 分诊结果 / 用户媒体（NFR5）。
 *
 * @param userId  注销匿名化后可空
 * @param endedAt 终态时间（{@code ConsultSession.terminalAt()}：中断取 interruptedAt，否则 updatedAt 兜底 createdAt）
 * @param stars   用户评分（1-5，无评分为 null）
 * @param comment 评分文字（无为 null）
 */
public record ConsultSessionMetaRow(long sessionId, Long userId, Long vetId, Instant startedAt,
        Instant endedAt, String status, Integer stars, String comment) {
}
