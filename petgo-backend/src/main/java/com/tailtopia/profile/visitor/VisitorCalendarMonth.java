package com.tailtopia.profile.visitor;

import java.util.List;

/**
 * 访客日历的一个月（V1.1.6 Story 2.2）。
 *
 * <p>与作者态同口径：<b>只返回有记录的日子</b>，无记录日与未来日由前端自行补格。
 * 区别在于「有记录」对访客而言<b>只看 Diary</b> —— 见 {@link VisitorDayCell}。
 *
 * @param year 年
 * @param month 月（1~12）
 * @param days 有记录的日子，按 day 升序
 */
public record VisitorCalendarMonth(int year, int month, List<VisitorDayCell> days) {
}
