package com.tailtopia.triage.domain;

/**
 * 分诊详建解锁来源（Story 2.2）。落库 varchar UPPER_SNAKE（照 {@link DangerLevel}/{@link TriageStatus} 范式）。
 *
 * <ul>
 *   <li>{@code LOCKED}：详建（SARAN PERAWATAN = advice/medicationRef）默认锁定，待解锁。生成成功
 *       （{@code markDone}）时初始化为此；PENDING/FAILED 任务 {@code unlock_source} 恒 NULL（无可解锁详建）。</li>
 *   <li>{@code FREE_QUOTA}：用每月免费额度解锁（Story 2.1/2.3）。{@code unlock_channel} 恒 NULL。</li>
 *   <li>{@code PAID}：付费解锁（Story 2.3）。{@code unlock_channel} 必有值（QRIS/PAWCOIN）。</li>
 * </ul>
 *
 * <p><b>一经写入不可覆盖</b>（架构 L105）：仅允许 {@code LOCKED/NULL → FREE_QUOTA/PAID} 一次性跃迁
 * （见 {@link TriageTask#unlock}）。<b>红色评级详建永不锁</b>与此枚举无关——红色放行判定在响应层
 * （{@code TriageResultResponse}）单点，即使 {@code unlock_source=LOCKED} 也放行（FR-43C）。
 */
public enum UnlockSource {
    LOCKED,
    FREE_QUOTA,
    PAID
}
