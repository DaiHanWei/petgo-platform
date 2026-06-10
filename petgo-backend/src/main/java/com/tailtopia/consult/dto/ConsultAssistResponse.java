package com.petgo.consult.dto;

import java.util.List;

/**
 * FR-5 兽医辅助工具（Story 5.5）。
 *
 * <p>{@code aiReferenceReply} 仅供兽医参考、<b>不自动发给用户</b>（NFR-9，兽医「采用」后可编辑再发）。
 * {@code historySummaries} 为按症状关键词匹配的历史判断摘要——<b>G-2 冷启动空库返回空列表</b>，
 * 历史匹配算法为后置增强。
 */
public record ConsultAssistResponse(String aiReferenceReply, List<String> historySummaries) {
}
