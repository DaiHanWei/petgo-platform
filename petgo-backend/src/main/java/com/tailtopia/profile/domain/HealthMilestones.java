package com.tailtopia.profile.domain;

import java.util.Set;

/**
 * 「健康类里程碑」的**单一定义处**（V1.1.2 Story 5.1 · FR-86；V1.3.0 Story 1.2 补齐通用宠物）。
 *
 * <p>集合 = 猫狗的 <b>M3 疫苗 · M4 驱虫 · M5 第一次看兽医 · M9 绝育</b>，
 * 加通用宠物的 <b>G-M1 第一次看兽医 · G-M2 第一次健康检查 / 疫苗</b>。
 *
 * <p>🔴 <b>V1.3.0 Story 1.2 起按完整 code 列举，不再按后缀</b>（AD-A4 / AD-A5）。原因就是这两条
 * 通用宠物节点：FR-86 当初按后缀 {@code M3/M4/M5/M9} 取消打卡，而通用清单把「看兽医」放在
 * {@code G-M1}、「健康检查 / 疫苗」放在 {@code G-M2}，两个后缀都不命中 —— 于是同一件事，猫狗
 * 要真问诊 / 真录记录才点亮，其他宠物点一下「已打卡」就行。这是规则漏网，不是有意的物种差异。
 * 按后缀判还有反向风险：通用清单的 {@code G-M3} 是「陪伴满 30 天」、{@code G-M4} 是「记录满 10 条」，
 * 跟健康毫无关系，却会因为撞上猫狗号位被判成健康类。
 *
 * <p>⚠️ <b>不得各处再写一份</b>。以下都引用本类，日后增减健康类里程碑只改这里：
 * <ul>
 *   <li>Story 5.1 —— 健康记录 / 兽医咨询触发自动完成的映射；</li>
 *   <li>Story 5.2 —— 后端**拒绝健康类打卡**的护栏与候选过滤（NFR-11）；</li>
 *   <li>Story 6.1 —— 埋点 T-12 的线上校验；</li>
 *   <li>{@code TimelineClassifier} —— 时间线展示抑制集合，由本集合**派生**（额外含 S4）。</li>
 * </ul>
 * 客户端另有一份等长的 {@code kAutoOnlyHealthMilestoneCodes}（`petgo_app/.../health_milestones.dart`），
 * 增减时必须同步 —— 后端拒绝、前端隐藏，两半缺一不可。
 */
public final class HealthMilestones {

    private HealthMilestones() {
    }

    /**
     * 健康类里程碑的**完整 code**（自动达成、且**禁止打卡**）。
     *
     * <p>三张清单成员数不同是正常的：猫狗各 4 条，通用宠物只有 2 条 —— 通用清单本就没有
     * 独立的驱虫 / 绝育节点。**别为了"对齐"给通用宠物硬凑两条。**
     */
    public static final Set<String> CODES = Set.of(
            "C-M3", "C-M4", "C-M5", "C-M9",
            "D-M3", "D-M4", "D-M5", "D-M9",
            "G-M1", "G-M2");

    /** 该 code 是否为健康类里程碑（自动达成、且**禁止打卡**）。 */
    public static boolean isHealthMilestone(String code) {
        return code != null && CODES.contains(code);
    }
}
