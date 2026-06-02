package com.petgo.consult.dto;

/**
 * 兽医咨询可用性（Story 5.2，AC3）。
 *
 * <p>护栏：<b>只回 {@code vetOnline} bool，绝不回精确在线人数</b>（架构 FR-4B：概率性展示、不暴露实时人数）。
 * 完整离线软引导（恢复时段 + 「先用 AI 分诊」）在 Story 5.3 扩展。
 */
public record ConsultAvailabilityResponse(boolean vetOnline) {
}
