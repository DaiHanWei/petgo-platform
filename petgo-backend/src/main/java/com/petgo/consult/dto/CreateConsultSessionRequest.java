package com.petgo.consult.dto;

/**
 * 发起咨询请求（Story 5.3）。本故事仅 DIRECT 发起，无额外负载；
 * AI 升级路径（source=AI_UPGRADE + 上下文）由 Story 5.4 扩展。
 */
public record CreateConsultSessionRequest() {
}
