package com.tailtopia.triage.dto;

/**
 * 本月免费额度状态（Story 2.1，{@code GET /api/v1/me/free-quota}）。供前端 paywall（2-4）与展示。
 *
 * @param period    当月 {@code YYYY-MM}（WIB）
 * @param limit     本月免费额度上限（配置值 clamp 到 [0,35]）
 * @param used      已用次数（无当月行则 0）
 * @param remaining 剩余 = {@code max(0, limit - used)}
 */
public record FreeQuotaView(String period, int limit, int used, int remaining) {
}
