package com.tailtopia.consult.dto;

import java.time.Instant;

/**
 * 问诊历史统一条目（Story 5.8）。{@code type=AI|VET}，按 {@code date} 倒序混排。
 *
 * <p>AI 项：{@code dangerLevel}+{@code symptomSummary}+{@code triageId}。
 * VET 项：{@code vetDisplayName}+{@code sessionSummary}+{@code userStars}(null=未评分)+{@code archived}
 * +{@code terminalState}(CLOSED/INTERRUPTED)+{@code closedReason}/{@code interruptedReason}+{@code sessionId}。
 * 历史保留<b>独立于存档</b>（{@code archived} 仅标记位）。Jackson NON_NULL 省略空字段。
 */
public record ConsultHistoryItem(
        String type,
        Instant date,
        // AI
        Long triageId,
        String dangerLevel,
        String symptomSummary,
        // VET
        Long sessionId,
        String vetDisplayName,
        String sessionSummary,
        Integer userStars,
        Boolean archived,
        String terminalState,
        String closedReason,
        String interruptedReason) {

    public static ConsultHistoryItem ai(long triageId, String dangerLevel, String symptomSummary, Instant date) {
        return new ConsultHistoryItem("AI", date, triageId, dangerLevel, symptomSummary,
                null, null, null, null, null, null, null, null);
    }

    public static ConsultHistoryItem vet(long sessionId, String vetDisplayName, String sessionSummary,
            Integer userStars, boolean archived, String terminalState, String closedReason,
            String interruptedReason, Instant date) {
        return new ConsultHistoryItem("VET", date, null, null, null,
                sessionId, vetDisplayName, sessionSummary, userStars, archived,
                terminalState, closedReason, interruptedReason);
    }
}
