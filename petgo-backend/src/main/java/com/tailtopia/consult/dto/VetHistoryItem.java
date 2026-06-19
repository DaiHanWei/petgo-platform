package com.tailtopia.consult.dto;

import java.time.Instant;

/**
 * 兽医工作台「历史」列表项（History Tab）。终态会话（CLOSED/INTERRUPTED）+ 用户评分摘要。
 *
 * <p>{@code summary} V1 取 AI 上下文症状快照（DIRECT 无则 null，与用户侧历史一致）；
 * {@code dangerLevel} 取 AI 初判（GREEN/YELLOW）；{@code stars}/{@code reviewText} 来自评分（未评则 null）。
 * 宠物身份经 service 跨模块只读端口富化（注销匿名化后 user_id 已剥 → petName/ownerHandle 为 null）。
 * Jackson NON_NULL 省略空字段。
 */
public record VetHistoryItem(
        long sessionId,
        String petName,
        String petSpecies,
        String ownerHandle,
        Instant date,
        Integer stars,
        String reviewText,
        String terminalState,
        String summary,
        String dangerLevel) {
}
