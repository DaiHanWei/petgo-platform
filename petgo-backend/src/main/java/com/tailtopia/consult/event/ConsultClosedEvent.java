package com.tailtopia.consult.event;

import java.time.LocalDate;
import java.util.List;

/**
 * 会话已关闭事件（Story 5.6）。CLOSED 时发布，触发：
 * <ul>
 *   <li>IM→OSS 存档桥接（{@code ImToOssArchiver} 复制聊天媒体到私密桶档案路径，FR-16）。</li>
 *   <li>profile 模块订阅落地成长档案（Epic 2，跨模块经事件，不直调 repository）——
 *       归档一条 {@code VET_CONSULT} 健康事件，供成长日历显示问诊标（Bug 20260701-139 / 091）。</li>
 * </ul>
 * 过去式命名。{@code imConversationId}/{@code aiImageRefs} 供存档拉取媒体（真实拉取属 L2）。
 * {@code eventDate}/{@code symptomSummary}/{@code aiLevel}/{@code adviceSummary} 供 profile 侧写健康事件
 * （随事件携带，避免 profile 反向依赖 consult 领域；健康数据仅按需，绝不进日志）。{@code petId} 会话不持有，
 * 由 profile 侧按 {@code userId} 反查（V1 单宠物）。
 */
public record ConsultClosedEvent(
        long sessionId,
        long userId,
        Long vetId,
        Long petId,
        String imConversationId,
        List<String> aiImageRefs,
        boolean rated,
        LocalDate eventDate,
        String symptomSummary,
        String aiLevel,
        String adviceSummary) {
}
