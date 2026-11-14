package com.tailtopia.consult.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 问诊历史统一条目（Story 5.8）。{@code type=AI|VET}，按 {@code date} 倒序混排。
 *
 * <p>AI 项：{@code dangerLevel}+{@code symptomSummary}+{@code triageId}+{@code archived}。
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
        String interruptedReason,
        // ===== V1.5.0 健康记录提醒 Story 2.5：VET_VISIT（Kontrol dokter）条目，仅 1.5.0 客户端 =====
        Long recordId,
        Long petId,
        String petName,
        LocalDate eventDate,
        String note,
        LocalDate nextDate,
        Boolean overdue,
        String recordStatus) {

    public static ConsultHistoryItem ai(long triageId, String dangerLevel, String symptomSummary,
            boolean archived, Instant date) {
        return new ConsultHistoryItem("AI", date, triageId, dangerLevel, symptomSummary,
                null, null, null, null, archived, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /**
     * Kontrol dokter（V1.5.0 Story 2.5，架构增量 §6.3）：{@code date} = event date 当天 + 记录创建时刻的 WIB 时分秒（排序与
     * 游标用）；{@code nextDate} = 显示用 Next date，{@code overdue} = 状态为 OVERDUE。
     */
    public static ConsultHistoryItem vetVisit(long recordId, long petId, String petName, LocalDate eventDate,
            String note, LocalDate nextDate, String recordStatus, Instant date) {
        return new ConsultHistoryItem("VET_VISIT", date, null, null, null,
                null, null, null, null, null, null, null, null,
                recordId, petId, petName, eventDate, note, nextDate, "OVERDUE".equals(recordStatus), recordStatus);
    }

    public static ConsultHistoryItem vet(long sessionId, String vetDisplayName, String sessionSummary,
            Integer userStars, boolean archived, String terminalState, String closedReason,
            String interruptedReason, Instant date) {
        return new ConsultHistoryItem("VET", date, null, null, null,
                sessionId, vetDisplayName, sessionSummary, userStars, archived,
                terminalState, closedReason, interruptedReason,
                null, null, null, null, null, null, null, null);
    }
}
