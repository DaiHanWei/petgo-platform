package com.tailtopia.profile.event;

import java.util.List;

/**
 * 账号级里程碑完成领域事件（V1.4.2 social-topic Story 2.1 · D-12 / P-3，过去式）。每次 flag 首次登记成功发<b>一个</b>
 * （与宠物数无关），供通知（1 条 {@code MILESTONE_SM_NODE}）、成长值（1 条 {@code MILESTONE_S}）、埋点消费。
 *
 * <p>账号级<b>不走</b> {@link MilestoneCompletedEvent}：那条链按宠物发，2 只宠物 = 2 条通知 + 2 次成长值。
 *
 * @param userId     账号 id
 * @param flagKey    {@code AccountMilestoneFlag.name()}（如 {@code FIRST_FOLLOW_USER}）
 * @param petIds     本次实际写了完成行的宠物 id（宠物创建顺序；可为空表 = 无 ACTIVE 宠物或 roster 行缺失）
 * @param codes      与 {@code petIds} 同序的完整 code
 * @param targetCode 通知 {@code targetRef}：账号下第一只 ACTIVE 宠物物种的编码（R-4）；无 ACTIVE 宠物 → null
 */
public record AccountMilestoneCompletedEvent(long userId, String flagKey, List<Long> petIds, List<String> codes,
        String targetCode) {

    public AccountMilestoneCompletedEvent {
        petIds = petIds == null ? List.of() : List.copyOf(petIds);
        codes = codes == null ? List.of() : List.copyOf(codes);
    }
}
