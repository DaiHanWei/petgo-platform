package com.tailtopia.admin.pin.dto;

/**
 * B3 顶置管理摘要条三格（V1.3.0 Story 7.3 · AC1）：生效中 · 待生效 · 已结束，随当前筛选联动。
 *
 * <p>🔴 三个数**在应用层按 {@code SchedulePhase} 数**，不另写一份 SQL 时间比较 ——
 * 「待生效 / 生效中 / 已结束」的判定唯一来源是 {@code ScheduleWindow}（含「提前结束」这种
 * 光看 {@code starts_at/ends_at} 判不出来的情况）。用 SQL 再算一遍就等于给同一个概念留两份口径，
 * 而它们迟早会分岔。坑位排期是低基数数据（一个坑位的全部历史），整表读一次不是问题。
 */
public record PinSummary(long active, long pending, long ended) {
}
