package com.tailtopia.profile.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 里程碑用户打卡「已打卡」内容关联选择器候选（Story 8.4 · FR-42）。仅该宠物已发布的**成长日历**内容；
 * 已关联其它里程碑的 {@code linked=true}（前端置灰不可选）。Jackson NON_NULL：无图/无正文省略。
 *
 * @param contentId     成长日历内容 id（content 模块对外用数字 id，与 Feed 一致）
 * @param firstImageUrl 首图（可空 → 省略）
 * @param eventDate     事件日期（可空 → 省略）
 * @param text          正文摘要（可空 → 省略）
 * @param linked        是否已被关联（true → 置灰不可选）
 */
public record MilestoneCheckinCandidateResponse(
        long contentId,
        String firstImageUrl,
        LocalDate eventDate,
        String text,
        boolean linked) {

    /** 候选信封。 */
    public record Page(List<MilestoneCheckinCandidateResponse> items) {
    }
}
