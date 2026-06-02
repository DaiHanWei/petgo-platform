package com.petgo.consult.dto;

/**
 * 发起咨询请求（Story 5.3 建，Story 5.4 扩展）。
 *
 * <p>{@code source} 缺省 DIRECT（用户直接发起）；{@code AI_UPGRADE} 时须带 {@code triageTaskId}，
 * 后端经 triage service 拉评级/描述/图片组装上下文（前端不重传）。
 */
public record CreateConsultSessionRequest(String source, Long triageTaskId) {

    public boolean isAiUpgrade() {
        return "AI_UPGRADE".equalsIgnoreCase(source);
    }
}
