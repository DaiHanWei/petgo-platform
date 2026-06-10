package com.petgo.profile.dto;

import java.util.List;

/**
 * 成长档案日历月视图（Story 2.4 R2 · F9）。按 {@code event_date} 聚合的当月有记录日格子。
 *
 * <p>仅返回**有记录**的日（有快乐时刻或健康事件）；无记录日由前端按月历网格补「+」引导，
 * 未来日由前端按当天置灰（后端不返回未来日记录，因 event_date 不可未来）。
 *
 * @param year  年
 * @param month 月（1-12）
 * @param days  有记录日格子列表（按 day 升序）
 */
public record CalendarMonthResponse(int year, int month, List<DayCell> days) {

    /**
     * 单日格子。
     *
     * @param day              日（1-31）
     * @param firstImageUrl    该日最早 created_at 快乐时刻的首图（无图/无快乐时刻为 null）
     * @param hasHappyMoment   当日有快乐时刻
     * @param hasHealthEvent   当日有健康事件（右下角 🏥 角标）
     */
    public record DayCell(int day, String firstImageUrl, boolean hasHappyMoment, boolean hasHealthEvent) {
    }
}
