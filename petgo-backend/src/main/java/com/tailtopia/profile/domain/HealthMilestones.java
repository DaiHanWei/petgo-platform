package com.tailtopia.profile.domain;

import java.util.Set;

/**
 * 「健康类里程碑」的**单一定义处**（V1.1.2 Story 5.1 · FR-86）。
 *
 * <p>集合 = <b>M3 疫苗 · M4 驱虫 · M5 第一次看兽医 · M9 绝育</b>，猫 C / 狗 D / 通用 G 三系同规则
 * （code 形如 {@code C-M3} / {@code D-M9} / {@code G-M5}）。
 *
 * <p>⚠️ <b>不得各处再写一份</b>。以下三处都引用本类，日后增减健康类里程碑只改这里：
 * <ul>
 *   <li>Story 5.1 —— 健康记录 / 兽医咨询触发自动完成的映射；</li>
 *   <li>Story 5.2 —— 后端**拒绝健康类打卡**的护栏与候选过滤（NFR-11）；</li>
 *   <li>Story 6.1 —— 埋点 T-12 的线上校验。</li>
 * </ul>
 * （前端 `TimelineClassifier` 另有一份**用途不同**的集合：它多含 S4「第一次保存兽医问诊结论」，
 * 因为那是「当天有健康条目就由胶囊承载」的展示规则，不是「取消打卡」的功能规则 —— 两者刻意分开。）
 */
public final class HealthMilestones {

    private HealthMilestones() {
    }

    /** 健康类里程碑的 code 后缀（不含系列前缀）。 */
    public static final Set<String> SUFFIXES = Set.of("M3", "M4", "M5", "M9");

    /** 该 code 是否为健康类里程碑（自动达成、且**禁止打卡**）。 */
    public static boolean isHealthMilestone(String code) {
        if (code == null) {
            return false;
        }
        int dash = code.lastIndexOf('-');
        return SUFFIXES.contains(dash >= 0 ? code.substring(dash + 1) : code);
    }
}
