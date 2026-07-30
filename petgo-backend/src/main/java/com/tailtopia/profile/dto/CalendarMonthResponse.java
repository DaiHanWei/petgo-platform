package com.tailtopia.profile.dto;

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
     * <p>前端角标优先级（bug 20260722-352）：diary 图（{@code firstImageUrl}）&gt; 问诊（{@code hasHealthEvent}）
     * &gt; 健康记录分类图标（{@code healthRecordType}）。
     *
     * @param day              日（1-31）
     * @param firstImageUrl    该日最早 created_at 快乐时刻的首图（无图/无快乐时刻为 null）
     * @param hasHappyMoment   当日有快乐时刻
     * @param hasHealthEvent   当日有健康事件（问诊；🏥 角标）
     * @param healthRecordType 当日健康记录分类枚举名（VACCINE/DEWORM/MENSTRUATION/NEUTER/CUSTOM），无则 null
     *                         —— 前端映射为与健康记录页一致的分类小图标
     */
    public record DayCell(int day, String firstImageUrl, boolean hasHappyMoment, boolean hasHealthEvent,
            String healthRecordType) {
    }
}
