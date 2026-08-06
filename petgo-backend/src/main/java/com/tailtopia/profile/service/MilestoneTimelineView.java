package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.MilestoneLevel;
import java.time.Instant;

/**
 * 里程碑完成的**只读时间线视图**（Story 3.2 · AC1）。
 *
 * <p>只读镜像：时间线里只展示，不提供编辑入口；FR-42 里程碑列表页的既有功能、编辑权限、
 * 数据生命周期规则一概不变。
 *
 * @param code 里程碑稳定 code（如 {@code C-L2}）。对外只给 code，不给自增 id
 * @param level S / M / L（类③ banner 按等级配色）
 * @param completedAt 完成时间戳（里程碑无「发布时间」概念，同日排序取此值）
 * @param linkedContentId 用户打卡关联的成长日历内容 id；系统自动完成为 {@code null}
 *        —— 非空即类②（由那条内容承载样式，里程碑本身不出条目）
 */
public record MilestoneTimelineView(
        String code,
        MilestoneLevel level,
        Instant completedAt,
        Long linkedContentId) {
}
