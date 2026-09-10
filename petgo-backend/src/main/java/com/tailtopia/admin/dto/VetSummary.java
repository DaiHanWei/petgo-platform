package com.tailtopia.admin.dto;

/**
 * B20 兽医账号摘要条四格（V1.3.0 Story 9.1a · AC1）：总数 · 在线 · 资质待审 · 已封禁。
 *
 * @param total       当前筛选集里的兽医数（「随筛选联动」= 对筛选后的集合统计，不是全库）
 * @param online      其中在线的（ONLINE 含 BUSY —— 忙碌也是在线，与列表筛选的口径逐字一致）
 * @param qualPending 资质 {@code UNDER_REVIEW}（材料交了等运营审）的条数。
 *                    🔴 不含 {@code PENDING_COMPLETION}（等的是兽医补材料，不是运营），
 *                    也不含 {@code EXPIRING_SOON} / {@code EXPIRED}（到期是另一件事，页首有预警横幅）——
 *                    混进去会让这个「待办数」永远清不完。
 * @param banned      已封禁的条数
 */
public record VetSummary(long total, long online, long qualPending, long banned) {
}
