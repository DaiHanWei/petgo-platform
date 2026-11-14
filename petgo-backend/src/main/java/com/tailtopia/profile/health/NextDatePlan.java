package com.tailtopia.profile.health;

import java.time.LocalDate;

/**
 * 一条记录的 Next date 方案（V1.5.0 Story 1.2，架构增量 §6.1 preview 出参的核心部分）。
 *
 * @param allowed       该类型 / 宠物有没有 Next date 字段（NEUTER、猫 / 公的 / 性别空 / 非母狗的发情 → false）
 * @param locked        发情周期后续记录：只读显示本周期首条的 Next date
 * @param lockedNextDate locked 时要显示的日期（首条存的值，可空）
 * @param suggestion    预填建议（无 → null；OTHER 物种恒 null）
 */
public record NextDatePlan(boolean allowed, boolean locked, LocalDate lockedNextDate, NextDateSuggestion suggestion) {

    static final NextDatePlan NOT_ALLOWED = new NextDatePlan(false, false, null, null);
    static final NextDatePlan NO_SUGGESTION = new NextDatePlan(true, false, null, null);
}
