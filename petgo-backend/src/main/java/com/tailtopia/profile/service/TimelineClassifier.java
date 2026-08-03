package com.tailtopia.profile.service;

import com.tailtopia.profile.dto.TimelineItemResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 时间线五类分类器（Story 3.2 · FR-82 · AD-2）。**纯函数、无任何依赖、无任何写库能力。**
 *
 * <p>⚠️ <b>安全攸关：分类结果严禁落库</b>（AD-2 Rule 2）。本类刻意做成无状态静态工具且
 * <b>不持有任何 Repository / EntityManager</b> —— 结构上就不可能把 {@code itemType} 写进表。
 * 反例代价：用户问诊后跳过存档（此时 M5 是类③ banner），日后补存，若 banner 已落库则不会消失，
 * 同一件事在时间线出现两条。
 *
 * <h2>五步优先级（AC2，命中即停）</h2>
 * <ol>
 *   <li>该条目对应一条**结构化健康记录或问诊存档** → 类④ {@code HEALTH_RECORD}</li>
 *   <li>否则是**身份证首次生成** → 类⑤ {@code ID_CARD_ISSUED}</li>
 *   <li>否则是里程碑完成，且**用户打卡绑定了一条 Diary 内容** → 类② {@code HAPPY_MOMENT_MILESTONE}
 *       （由那条内容承载样式，里程碑**不额外出条目**）</li>
 *   <li>否则是里程碑完成（系统自动、无绑定内容）→ 类③ {@code MILESTONE_BANNER}</li>
 *   <li>否则是普通 Diary 内容 → 类① {@code HAPPY_MOMENT}</li>
 * </ol>
 *
 * <h2>「当天视角」与判定依据（AC4 · AD-2 Rule 3）</h2>
 * <p>判定依据是「**这一天有没有对应的健康记录条目**」，<b>不是</b>里程碑声明的触发方式字段
 * （{@code trigger_type} / {@code source}）—— 同一个疫苗里程碑无论走自动路径还是历史打卡路径完成，
 * 展示结果必须一致。所以分类要先按日聚合「这一天有无健康条目」，再逐条判定。
 *
 * <p><b>抑制范围只限健康类里程碑</b>（本 Story 的实现判断，记录在案）：PRD 原文是「**由这些记录触发完成的**
 * 里程碑不再单独生成时间线条目」。若把「当天有健康记录」无差别地用于所有里程碑，那么某天正好录了疫苗，
 * 当天的「100 天纪念」banner 会被一起吃掉 —— 明显不是意图。因此仅对**健康类**里程碑
 * （见 {@link #HEALTH_MILESTONE_SUFFIXES}）做「当天有健康条目即让胶囊承载」的抑制。
 */
public final class TimelineClassifier {

    private TimelineClassifier() {
    }

    /**
     * 健康类里程碑的 code 后缀（含猫 C / 狗 D / 通用 G 三系）：
     * M3 疫苗 · M4 驱虫 · M5 第一次看兽医 · M9 绝育 · S4 第一次保存兽医问诊结论。
     *
     * <p>只有这五类会被「当天已有健康条目」抑制（由类④ 胶囊承载）；其余里程碑照常出 banner。
     */
    static final Set<String> HEALTH_MILESTONE_SUFFIXES = Set.of("M3", "M4", "M5", "M9", "S4");

    /**
     * 分类并归并成一份条目列表（未排序，排序由调用方按全局序统一处理）。
     *
     * @param contents 类①/② 候选：Diary 内容（已按锚点取数）
     * @param healthItems 类④：问诊存档 + 结构化健康记录（两者共用类④）
     * @param milestones 里程碑完成的只读视图
     * @param idCardIssues 类⑤：身份证首次生成（0 或 1 条）
     */
    public static List<TimelineItemResponse> classify(
            List<ContentCandidate> contents,
            List<TimelineItemResponse> healthItems,
            List<MilestoneTimelineView> milestones,
            List<TimelineItemResponse> idCardIssues) {

        // ① 当天有无健康条目（结构化记录 + 问诊存档）——AC4 的判定依据，按日聚合。
        Set<LocalDate> daysWithHealthItem = new HashSet<>();
        for (TimelineItemResponse h : healthItems) {
            daysWithHealthItem.add(h.effectiveDate());
        }

        // ③ 打卡绑定：内容 id → 里程碑。绑定者由内容承载样式，里程碑本身不出条目。
        Map<Long, MilestoneTimelineView> byLinkedContent = new HashMap<>();
        for (MilestoneTimelineView m : milestones) {
            if (m.linkedContentId() != null) {
                byLinkedContent.put(m.linkedContentId(), m);
            }
        }

        List<TimelineItemResponse> out = new ArrayList<>(
                contents.size() + healthItems.size() + milestones.size() + idCardIssues.size());

        out.addAll(healthItems); // 类④（工厂已置 itemType=HEALTH_RECORD）
        out.addAll(idCardIssues); // 类⑤

        for (ContentCandidate c : contents) {
            MilestoneTimelineView linked = c.postId() == null ? null : byLinkedContent.get(c.postId());
            if (linked != null) {
                // 类②：同一条内容 + 金徽章角标（**不新增条目**，AC2 末条）
                out.add(TimelineItemResponse.happyMomentWithMilestone(c.postId(), c.createdAt(),
                        c.eventDate(), c.imageUrls(), c.text(), linked.code(),
                        linked.level() == null ? null : linked.level().name()));
            } else {
                out.add(TimelineItemResponse.happyMoment(c.postId(), c.createdAt(), c.eventDate(),
                        c.imageUrls(), c.text()));
            }
        }

        for (MilestoneTimelineView m : milestones) {
            if (m.linkedContentId() != null) {
                continue; // 已由类② 的内容承载
            }
            LocalDate day = m.completedAt().atZone(ZoneOffset.UTC).toLocalDate();
            if (isHealthMilestone(m.code()) && daysWithHealthItem.contains(day)) {
                // 当天已有健康条目 → 由类④ 胶囊承载，里程碑不单独出条目（避免同一件事两条）。
                // ⚠️ 这条判定用的是「当天有无健康条目」这一**实时事实**，不是任何落库的分类结果——
                // 因此「跳过存档 → 补存」后立即重查，banner 自然消失（AC3）。
                continue;
            }
            out.add(TimelineItemResponse.milestoneBanner(m.completedAt(), m.code(),
                    m.level() == null ? null : m.level().name()));
        }

        return out;
    }

    /** code 形如 {@code C-M3} / {@code D-S4} / {@code G-M9}：取「-」后的后缀判定是否健康类。 */
    static boolean isHealthMilestone(String code) {
        if (code == null) {
            return false;
        }
        int dash = code.lastIndexOf('-');
        String suffix = dash >= 0 ? code.substring(dash + 1) : code;
        return HEALTH_MILESTONE_SUFFIXES.contains(suffix);
    }

    /**
     * 类①/② 的内容候选（分类前的中间形态：还不知道自己是①还是②）。
     *
     * <p>刻意不直接用 {@link TimelineItemResponse} 承载，避免「先建成类①再改写成类②」的可变语义
     * —— record 不可变，改写只能新建对象，容易漏字段。
     */
    public record ContentCandidate(
            Long postId,
            java.time.Instant createdAt,
            LocalDate eventDate,
            List<String> imageUrls,
            String text) {
    }
}
