package com.tailtopia.mention.dto;

import java.util.List;

/**
 * @ 候选集响应（V1.3.0 batch-b1 Story 3.1）。
 *
 * <p>包一层对象而不是裸数组 —— 将来要加字段（比如"还有更多"）不必改响应形状。
 *
 * <p>⚠️ <b>不分页</b>：不带 {@code q} 是一次性给完的最多 30 人快捷列表；带 {@code q}（V1.4.2 social-topic
 * Story 5.1）是「我关注 ∪ 关注我 ∪ 互动候选」三类范围内的最多 20 条匹配 —— 上限即边界，不翻页。
 * <b>不是全局用户搜索</b>：范围外的人搜不到（全局搜索归 1.5.4）。
 */
public record MentionCandidatesResponse(List<MentionCandidateView> items) {
}
