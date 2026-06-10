package com.tailtopia.notify.schedule;

import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.profile.dto.PetProfileSnapshot;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 定时推送计划器（Story 6.7 · 决策 F5）—— <b>纯逻辑、无副作用</b>，是 L0 金标可测的核心。
 *
 * <p>给定「当前日期 + 档案快照 + 已推标记集」，产出当日应投递的推送集合：
 * <ul>
 *   <li>生日（FR-40）：宠物生日<b>前 1 天</b>触发（month/day 比对，含 2/29→平年 2/28 兜底），按年份去重。</li>
 *   <li>陪伴纪念日（FR-41）：建档满 {30,100,365} 天<b>当天</b>触发，按天数节点去重。</li>
 *   <li>里程碑节点（FR-42 仅推送切片）：V1 仅「第一个生日」(age==1，与生日同时点、独立 type/深链)，按节点去重。</li>
 * </ul>
 *
 * <p>本类<b>绝不</b>读写里程碑表/完成态/徽章（属里程碑 mini-epic，决策 F2 范围守护）——它只产出推送意图。
 */
@Component
public class ScheduledPushPlanner {

    /** 陪伴纪念日节点（天）。365 之后 V1 无更多节点。 */
    public static final List<Integer> ANNIVERSARY_NODES = List.of(30, 100, 365);

    /** 「第一个生日」L 级里程碑节点 key。 */
    public static final String FIRST_BIRTHDAY_NODE = "FIRST_BIRTHDAY";

    /**
     * @param today        当前日期（注入便于 L0 测试）。
     * @param profiles     档案快照（经 profile 只读端口取）。
     * @param existingKeys 已推去重键集合（{@link PlannedPush#markKey()} 格式）。
     */
    public List<PlannedPush> plan(LocalDate today, List<PetProfileSnapshot> profiles, Set<String> existingKeys) {
        List<PlannedPush> out = new ArrayList<>();
        LocalDate tomorrow = today.plusDays(1);
        for (PetProfileSnapshot p : profiles) {
            // ① 生日（前 1 天）+ 第一个生日里程碑节点。
            if (p.birthday() != null && isAnnualDateOn(p.birthday(), tomorrow)) {
                int age = tomorrow.getYear() - p.birthday().getYear();
                if (age >= 1) {
                    addIfNew(out, existingKeys, p, NotificationType.PET_BIRTHDAY,
                            String.valueOf(tomorrow.getYear()), age);
                    if (age == 1) {
                        addIfNew(out, existingKeys, p, NotificationType.MILESTONE_NODE,
                                FIRST_BIRTHDAY_NODE, age);
                    }
                }
            }
            // ② 陪伴纪念日（当天恰为 30/100/365 天）。
            if (p.createdDate() != null) {
                long days = ChronoUnit.DAYS.between(p.createdDate(), today);
                if (days >= 0 && days <= Integer.MAX_VALUE && ANNIVERSARY_NODES.contains((int) days)) {
                    addIfNew(out, existingKeys, p, NotificationType.COMPANION_ANNIVERSARY,
                            String.valueOf(days), (int) days);
                }
            }
        }
        return out;
    }

    private void addIfNew(List<PlannedPush> out, Set<String> existingKeys, PetProfileSnapshot p,
            NotificationType type, String nodeKey, int number) {
        PlannedPush push = new PlannedPush(p.id(), p.ownerId(), type, nodeKey, p.name(), number);
        if (!existingKeys.contains(push.markKey())) {
            out.add(push);
        }
    }

    /** {@code date} 是否落在 {@code anchor} 的「年度纪念日」上（month/day 比对，2/29→平年 2/28 兜底）。 */
    private boolean isAnnualDateOn(LocalDate anchor, LocalDate date) {
        if (anchor.getMonthValue() == date.getMonthValue() && anchor.getDayOfMonth() == date.getDayOfMonth()) {
            return true;
        }
        // 闰日生日：平年在 2/28 纪念。
        return anchor.getMonthValue() == 2 && anchor.getDayOfMonth() == 29
                && date.getMonthValue() == 2 && date.getDayOfMonth() == 28 && !date.isLeapYear();
    }
}
