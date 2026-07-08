package com.tailtopia.namemoderation.domain;

/**
 * 名称审核状态机（内容审核 story 4，§5.2，落库 UPPER_SNAKE）。
 *
 * <pre>
 *                       ┌─(score&lt;0.6)──────────────► AUTO_PASSED (终态, 静默)
 *  提交/编辑 → SCORING ─┼─(0.6≤score&lt;0.8)─► MANUAL_PENDING(NORMAL) ─┬─运营 PASS──► RESOLVED_PASS (终态)
 *    (revision++)       ├─(score≥0.8/L1)──► MANUAL_PENDING(HIGH)   ─┴─运营 VIOLATION► RESOLVED_VIOLATION → 重置默认名
 *                       └─(三方降级, 重试耗尽)─► MANUAL_PENDING (fail-closed, risk=NULL)
 *  任意非终态被新提交取代 ─► SUPERSEDED (终态, 静默丢弃; 若在 MANUAL_PENDING 则移出队列)
 * </pre>
 *
 * <p>幂等：仅非终态（{@code SCORING}/{@code MANUAL_PENDING}）可被推进/取代；
 * {@code AUTO_PASSED}/{@code RESOLVED_*}/{@code SUPERSEDED}/{@code FAILED_TO_QUEUE} 不可再变。
 * 名称侧无「纯评分自动重置」路径——评分只决定入队与优先级，违规与否由运营裁定。
 */
public enum NameModerationStatus {
    /** 已建记录、正评分中（异步调三方）。 */
    SCORING,
    /** 低风险自动通过（终态，静默，不推送）。 */
    AUTO_PASSED,
    /** 入人工队列待运营处置（中/高风险或 fail-closed 降级）。 */
    MANUAL_PENDING,
    /** 运营判过（终态，静默，不推送）。 */
    RESOLVED_PASS,
    /** 运营判违规 → 已重置默认名 + 推送（终态）。 */
    RESOLVED_VIOLATION,
    /** 被更高 revision 新提交取代（终态，静默丢弃）。 */
    SUPERSEDED,
    /** 入队失败兜底（保留态，供告警/排障；本 story 正常路径不产生）。 */
    FAILED_TO_QUEUE;

    /** 是否为可推进的非终态（评分结果/运营处置/取代只作用于此）。 */
    public boolean isActive() {
        return this == SCORING || this == MANUAL_PENDING;
    }
}
