package com.tailtopia.admin.vetqual.domain;

/**
 * 兽医资质状态机（Story 2.1，AB-2H）。落库 varchar + UPPER_SNAKE，6 态：
 *
 * <ul>
 *   <li>{@link #PENDING_COMPLETION} 待完善（默认；资料未齐）。</li>
 *   <li>{@link #UNDER_REVIEW} 审核中（已提交待运营审核）。</li>
 *   <li>{@link #CERTIFIED} 已认证（SIPDH 合法执业，可接单）。</li>
 *   <li>{@link #REJECTED} 已驳回（运营驳回，附 reject_reason）。</li>
 *   <li>{@link #EXPIRING_SOON} 证件即将到期（≤30 天，仍可接单，仅预警）。</li>
 *   <li>{@link #EXPIRED} 证件已过期（不可接单）。</li>
 * </ul>
 *
 * <p>仅 {@link #CERTIFIED} / {@link #EXPIRING_SOON} 可接单（{@code canTakeConsult}）。
 */
public enum QualificationStatus {
    PENDING_COMPLETION,
    UNDER_REVIEW,
    CERTIFIED,
    REJECTED,
    EXPIRING_SOON,
    EXPIRED;

    /** 是否允许接单：仅已认证 / 即将到期（PRD：≤30 天仅预警不停接）。 */
    public boolean canTakeConsult() {
        return this == CERTIFIED || this == EXPIRING_SOON;
    }
}
