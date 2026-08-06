package com.tailtopia.content.event;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import java.time.Instant;

/**
 * 内容发布领域事件（Story 8.3，过去式）。经 {@code ApplicationEventPublisher} 进程内发布，
 * 供 profile 模块里程碑自动完成订阅（C-S2 首张成长日历照片 / C-S5 首条平台帖子 /
 * 计数类 M10·L5）——**content 不直调 profile 里程碑**（架构边界，经事件解耦）。
 *
 * @param postId                  新内容 id
 * @param authorId                作者 id（= 单宠用户 owner，里程碑按 owner 解析档案）
 * @param type                    内容类型（DAILY / GROWTH_MOMENT / KNOWLEDGE）
 * @param petId                   绑定宠物档案 id（仅 GROWTH_MOMENT 有值）
 * @param authorGrowthMomentCount 发布后该作者已发布成长日历总数（仅 GROWTH_MOMENT 时有意义，
 *                                供计数类里程碑 M10/L5 判定；非 GROWTH_MOMENT 为 0）
 * @param visibility              可见范围（V1.1.2 Story 4.1）。**S5「首条平台帖子」判定必需**：
 *                                Diary 开同步 = GROWTH_MOMENT + PUBLIC，对外可见即算平台发帖；
 *                                PRIVATE 只入作者自己的档案，不算（见
 *                                {@code MilestoneAutoCompleteListener#onContentPublished}）。
 *                                订阅方不得反查 content 表拿这个值——那会破掉事件解耦的模块边界。
 * @param createdAt               发布时间（UTC）
 */
public record ContentPublishedEvent(
        long postId,
        long authorId,
        ContentType type,
        Long petId,
        long authorGrowthMomentCount,
        ContentVisibility visibility,
        Instant createdAt) {
}
