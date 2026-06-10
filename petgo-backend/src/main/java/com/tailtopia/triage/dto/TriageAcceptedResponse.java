package com.tailtopia.triage.dto;

import com.tailtopia.triage.domain.TriageStatus;

/**
 * 分诊受理响应（Story 4.1）。{@code POST /triage} 返回 202 + 本体；{@code triageId} 仅授权本人可读，
 * 非公开枚举入口。
 *
 * @param triageId 任务 id
 * @param status   受理时态（PENDING）
 */
public record TriageAcceptedResponse(Long triageId, TriageStatus status) {

    public static TriageAcceptedResponse of(Long triageId, TriageStatus status) {
        return new TriageAcceptedResponse(triageId, status);
    }
}
