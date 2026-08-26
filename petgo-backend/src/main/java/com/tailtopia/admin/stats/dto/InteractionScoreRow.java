package com.tailtopia.admin.stats.dto;

import com.tailtopia.content.domain.ContentType;
import java.time.Instant;

/**
 * 互动积分榜的一行（V1.1.6 Story 15.1 · AB-3G）。
 *
 * @param likes    参与计分的点赞数。⚠️ 口径A 是**至今累计**、口径B 是**该区间内新增** ——
 *                 同一个字段名在两个口径下含义不同，这正是两个口径回答不同问题的地方
 * @param comments 同上
 * @param score    赞 × 1 + 评 × 5。🛡 <b>与首页推荐算法的互动度权重是两套东西</b>（AC7）——
 *                 一个是给人看的运营指标，一个是排序输入，不要因为"看起来都是互动分"就去对齐
 */
public record InteractionScoreRow(long postId, ContentType type, Long authorId,
        String textPreview, Instant publishedAt, long likes, long comments, long score) {

    /** 积分公式（AC1）。放在这里，两个口径共用一份 —— 各算一遍迟早分叉。 */
    public static long scoreOf(long likes, long comments) {
        return likes + comments * 5;
    }
}
