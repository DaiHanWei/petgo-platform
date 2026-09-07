package com.tailtopia.shared.schedule;

/**
 * 排期所处的阶段（V1.1.6 · AD-9）。
 *
 * <p><b>不落库</b> —— 这是查询时算出来的，不是一个状态列。后台列表要显示「待生效 / 生效中 / 已结束」，
 * 算的就是它。
 */
public enum SchedulePhase {
    /** 还没到开始时刻。 */
    PENDING,
    /** 当前时刻落在 {@code [开始, 结束)} 内。 */
    ACTIVE,
    /** 已到或已过结束时刻。 */
    ENDED
}
