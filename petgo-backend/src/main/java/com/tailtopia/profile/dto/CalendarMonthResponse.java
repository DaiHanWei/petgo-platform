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
     * <p>前端格子优先级（Story 3.4 · FR-84 · UX-DR11，**只显一个标记**）：
     * ① diary 带图 → 首图铺满（不叠角标）→ ② 有 diary 但全无图 → 通用 diary 标记 →
     * ③ 无 diary 有问诊 → 🏥 → ④ 只有结构化健康记录 → 单条用类型图标、**多条用通用医疗箱**
     * → ⑤ 无记录 → 「+」/置灰。
     *
     * <p>⚠️ **与时间线的优先级方向相反，且刻意不对齐**（AD-16）：时间线是逐条分类（同一天既有带图日记
     * 又有疫苗记录时出两条），日历是整天取一个代表标记（只显日记首图）。粒度不同所以规则不同 ——
     * **不得为了「统一」而对齐**。
     *
     * @param day               日（1-31）
     * @param firstImageUrl     该日最早 created_at 快乐时刻的首图（无图/无快乐时刻为 null）
     * @param hasHappyMoment    当日有快乐时刻（**纯文字日记也为 true** —— 前端判定优先级② 用它，
     *                          不能只看 firstImageUrl，否则纯文字日记会掉到问诊图标去，那正是要修的缺陷）
     * @param hasHealthEvent    当日有健康事件（问诊；🏥）
     * @param healthRecordType  当日**首条**结构化健康记录的分类枚举名，无则 null
     * @param healthRecordCount 当日结构化健康记录**条数**（Story 3.4 新增的唯一一维）：
     *                          多于一条时前端用通用医疗箱图标，具体各条进当天详情看
     */
    public record DayCell(int day, String firstImageUrl, boolean hasHappyMoment, boolean hasHealthEvent,
            String healthRecordType, int healthRecordCount) {
    }
}
