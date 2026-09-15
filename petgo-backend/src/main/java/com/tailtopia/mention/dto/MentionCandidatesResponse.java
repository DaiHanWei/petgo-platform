package com.tailtopia.mention.dto;

import java.util.List;

/**
 * @ 候选集响应（V1.3.0 batch-b1 Story 3.1）。
 *
 * <p>包一层对象而不是裸数组 —— 将来要加字段（比如"还有更多"）不必改响应形状。
 *
 * <p>⚠️ <b>没有分页参数、也没有关键词参数</b>：这是一次性给完的 30 人小表，
 * 昵称过滤在客户端于这批人之内做。<b>没有全局用户搜索</b>（AC5，搜索留在 1.6.0 未前移）。
 */
public record MentionCandidatesResponse(List<MentionCandidateView> items) {
}
