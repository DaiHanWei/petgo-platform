package com.tailtopia.consult.dto;

import com.tailtopia.consult.domain.ConsultSession;

/**
 * 兽医侧会话视图（Story 5.5，进行中会话）。含 IM 会话标识供客户端 SDK 加载对话。
 */
public record VetSessionView(
        long id,
        String status,
        String source,
        Long userId,
        String imConversationId,
        boolean hasAiContext) {

    public static VetSessionView of(ConsultSession s) {
        return new VetSessionView(s.getId(), s.getStatus().name(), s.getSource().name(),
                s.getUserId(), s.getImConversationId(), s.hasAiContext());
    }
}
