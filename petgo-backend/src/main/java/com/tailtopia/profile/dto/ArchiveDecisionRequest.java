package com.tailtopia.profile.dto;

import com.tailtopia.profile.domain.ArchiveDecision;
import com.tailtopia.profile.domain.HealthSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * 问诊存档决策请求（{@code POST /api/v1/health-events/archive-decisions}）。Story 2.5。
 *
 * <p>由 Epic 4/5 结束页（持有 sourceRef + 问诊结果）触发；{@code petId} 归属经 JWT 校验。
 * {@code ARCHIVED} 时带症状/评级/建议与可选 IM 图引用（复制到私密桶）；{@code SKIPPED} 仅落决策。
 *
 * @param sourceType  AI_TRIAGE | VET_CONSULT
 * @param sourceRef   问诊/会话 token（幂等键）
 * @param petId       归属宠物（须属当前用户）
 * @param decision    ARCHIVED | SKIPPED
 * @param eventDate   事件日期（null = 今天）
 * @param symptomSummary 症状摘要（健康数据）
 * @param aiLevel     AI 评级 GREEN/YELLOW/RED
 * @param adviceSummary  处理建议摘要（健康数据）
 * @param imImageRefs IM 聊天图引用（ARCHIVED 时复制到私密桶②，存自有 key）
 */
public record ArchiveDecisionRequest(
        @NotNull HealthSourceType sourceType,
        @NotBlank @jakarta.validation.constraints.Size(max = 64) String sourceRef,
        @NotNull Long petId,
        @NotNull ArchiveDecision decision,
        LocalDate eventDate,
        String symptomSummary,
        String aiLevel,
        String adviceSummary,
        List<String> imImageRefs) {
}
