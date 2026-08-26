package com.tailtopia.profile.visitor;

/**
 * 访客日历的一个格子（V1.1.6 Story 2.2）。
 *
 * <h2>🛡 三个字段，对照作者态的六个</h2>
 * 作者态的 {@code CalendarMonthResponse.DayCell} 还有
 * <b>{@code hasHealthEvent}</b>（当天有问诊存档）·
 * <b>{@code healthRecordType}</b>（当天首条健康记录的类型）·
 * <b>{@code healthRecordCount}</b>（当天健康记录条数）三个字段。
 * 访客侧另起一个 record，就是为了让它们<b>物理上不存在</b> ——
 * 哪怕只下发一个 {@code hasHealthEvent=true}，也等于告诉陌生人
 * 「这只宠物这天看过病」，而 PRD §2.9 里健康与问诊整块都是 ❌。
 *
 * <h2>⚠️ 「无记录日」的判据是「没有 Diary」</h2>
 * 只有健康记录、没有 Diary 的那一天，在访客侧<b>整格不出现</b>（接口只返回有记录日），
 * 而不是「出现一个没有标记的格子」。后者可以被数出来 ——
 * 访客会发现「这天有个空格子，说明有事发生但不给我看」。
 *
 * @param day 当月第几天（1~31）
 * @param firstImageUrl 当天最早一条快乐时刻的首图，<b>已去 EXIF</b>；当天无图则为 null
 * @param hasHappyMoment 当天有 Diary（含纯文字日记、含作者关闭同步的私密条目）
 */
public record VisitorDayCell(int day, String firstImageUrl, boolean hasHappyMoment) {
}
