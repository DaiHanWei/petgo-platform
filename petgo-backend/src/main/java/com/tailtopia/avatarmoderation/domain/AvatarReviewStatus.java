package com.tailtopia.avatarmoderation.domain;

/**
 * 头像审核流水线态（内容审核 story 5，§4.1，落库 UPPER_SNAKE）。
 *
 * <pre>
 *                       ┌─(图像 PASS, risk&lt;0.6)──────► AUTO_PASSED (终态, 静默, 不推送)
 *  换头像 → QUEUED ─────┼─(IMAGE_BLOCKED / risk≥0.6)─► MANUAL_PENDING ─┬─运营 PASS──────► RESOLVED (verdict=PASS)
 *   (avatar_url 版本键) ├─(三方降级, 重试耗尽)────────► MANUAL_PENDING ─┴─运营 VIOLATION─► RESOLVED (verdict=VIOLATION) → 重置默认头像
 *                       └─(版本键失配 / 被新提交取代)──► RESOLVED (verdict=STALE_DISCARDED, 静默丢弃, 不处置不推送)
 * </pre>
 *
 * <p>非终态 = {@code QUEUED} / {@code MANUAL_PENDING}（评分结果 / 运营处置 / 陈旧作废只作用于此）；
 * {@code AUTO_PASSED} / {@code RESOLVED} 为终态。头像侧无「纯评分自动判违规」路径——评分只决定入队与优先级，
 * 违规与否由运营在人工队列裁定（§5.3，与名称侧一致；V1 不做自动判违规作为误判缓冲，R2）。
 */
public enum AvatarReviewStatus {
    /** 已建记录、正评分中（异步调三方图像审核）。 */
    QUEUED,
    /** 低风险自动通过（终态，静默，不推送）。 */
    AUTO_PASSED,
    /** 入人工队列待运营处置（图像高置信违规 / 中高风险 / fail-closed 降级）。 */
    MANUAL_PENDING,
    /** 已终态化（运营判过/判违规、或陈旧作废）。verdict 区分具体结论。 */
    RESOLVED;

    /** 是否为可推进的非终态（评分结果 / 运营处置 / 陈旧作废只作用于此）。 */
    public boolean isActive() {
        return this == QUEUED || this == MANUAL_PENDING;
    }
}
