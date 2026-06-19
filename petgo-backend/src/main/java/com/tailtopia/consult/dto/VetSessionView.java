package com.tailtopia.consult.dto;

import com.tailtopia.consult.domain.ConsultSession;

/**
 * 兽医侧会话视图（Story 5.5，进行中会话）。含 IM 会话标识供客户端 SDK 加载对话。
 *
 * <p>宠物身份（{@code petName}/{@code petSpecies}/{@code petAgeMonths}/{@code ownerHandle}）经 service
 * 跨模块只读端口富化（顶栏展示）。<b>不含性别</b>，前端兜底隐藏（Jackson NON_NULL 省略 null）。
 */
public record VetSessionView(
        long id,
        String status,
        String source,
        Long userId,
        String imConversationId,
        boolean hasAiContext,
        String petName,
        String petSpecies,
        Integer petAgeMonths,
        String ownerHandle) {

    /**
     * 基础视图（无宠物身份）。写路径（接单/结束/退单）专用：写事务已提交，响应不挂跨模块身份富化，
     * 避免富化查询失败把已成功的写翻成 500。读路径（sessionView）走 service 富化的五参重载。
     */
    public static VetSessionView of(ConsultSession s) {
        return of(s, null, null, null, null);
    }

    public static VetSessionView of(ConsultSession s, String petName, String petSpecies,
            Integer petAgeMonths, String ownerHandle) {
        return new VetSessionView(s.getId(), s.getStatus().name(), s.getSource().name(),
                s.getUserId(), s.getImConversationId(), s.hasAiContext(),
                petName, petSpecies, petAgeMonths, ownerHandle);
    }
}
