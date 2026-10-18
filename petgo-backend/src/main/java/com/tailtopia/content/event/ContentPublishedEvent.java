package com.tailtopia.content.event;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import java.time.Instant;
import java.util.List;

/**
 * 内容发布领域事件（Story 8.3，过去式）。经 {@code ApplicationEventPublisher} 进程内发布，
 * 供 profile 模块里程碑自动完成订阅（C-S2 首张成长日历照片 / C-S5 首条平台帖子 /
 * 计数类 M10·L5）——**content 不直调 profile 里程碑**（架构边界，经事件解耦）。
 *
 * @param postId                  新内容 id
 * @param authorId                作者 id（= 单宠用户 owner，里程碑按 owner 解析档案）
 * @param type                    内容类型（DAILY / GROWTH_MOMENT / KNOWLEDGE）
 * @param petId                   绑定宠物档案 id（仅 GROWTH_MOMENT 有值）。V1.4.0 multi-pet：= {@code petIds} 的第一只
 *                                （宠物创建顺序，与 {@code content_posts.pet_id} 同一只）
 * @param authorGrowthMomentCount 发布后该作者已发布成长日历总数（仅 GROWTH_MOMENT 时有意义，
 *                                供计数类里程碑 M10/L5 判定；非 GROWTH_MOMENT 为 0）
 * @param visibility              可见范围（V1.1.2 Story 4.1）。**S5「首条平台帖子」判定必需**：
 *                                Diary 开同步 = GROWTH_MOMENT + PUBLIC，对外可见即算平台发帖；
 *                                PRIVATE 只入作者自己的档案，不算（见
 *                                {@code MilestoneAutoCompleteListener#onContentPublished}）。
 *                                订阅方不得反查 content 表拿这个值——那会破掉事件解耦的模块边界。
 * @param createdAt               发布时间（UTC）
 * @param petIds                  关联的全部宠物 id（V1.4.0 multi-pet Story 3.1 · AD-10，<b>末尾追加</b>），按宠物创建顺序；
 *                                不为 null，Moment / Tips 与未关联宠物的 Diary 为空表。① 成长值流水在 ② 合并后改取本字段
 */
public record ContentPublishedEvent(
        long postId,
        long authorId,
        ContentType type,
        Long petId,
        long authorGrowthMomentCount,
        ContentVisibility visibility,
        Instant createdAt,
        List<Long> petIds) {

    public ContentPublishedEvent {
        petIds = petIds == null ? List.of() : List.copyOf(petIds);
    }

    /**
     * 7 参兼容构造器（V1.4.0 前的形态）：{@code petIds} 由 {@code petId} 推导。<b>只供既有测试</b>，
     * main 代码一律用全参构造（传真实关联宠物）。
     */
    public ContentPublishedEvent(long postId, long authorId, ContentType type, Long petId,
            long authorGrowthMomentCount, ContentVisibility visibility, Instant createdAt) {
        this(postId, authorId, type, petId, authorGrowthMomentCount, visibility, createdAt,
                petId == null ? List.of() : List.of(petId));
    }
}
