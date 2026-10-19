package com.tailtopia.consult.dto;

import java.util.List;

/**
 * 发起付费问诊入队请求（Story 3.2 [OPEN] 收口 + D1/D2，2026-07-16）。
 *
 * <p>{@code source} 缺省 DIRECT（用户自填病例）；{@code AI_UPGRADE} 时须带 {@code triageTaskId}，
 * 后端经 triage service 拉评级/症状/图片组装上下文（<b>前端不重传</b>，照 Story 5.4 既有约定）。
 * AI_UPGRADE 时忽略 {@code symptomText}/{@code imageObjectKeys}。
 *
 * <p>签名与 {@link CreateConsultSessionRequest}（V1.0 会话入参）保持一致，便于两路径对照维护。
 * 图片传私密桶对象 key（前端已直传），<b>后端不收签名 URL</b>。
 *
 * <p>{@code petId}（V1.4.0 multi-pet Story 4.1 · AD-2，可空）：DIRECT 时为所选宠物（不属于本人 → 404），
 * 缺省（旧版 App）= 默认宠物；AI_UPGRADE 时宠物沿用该分诊任务，本字段仅作任务未挂宠物时的退路。
 */
public record CreateConsultationRequest(String source, Long triageTaskId,
        String symptomText, List<String> imageObjectKeys, Long petId) {

    public boolean isAiUpgrade() {
        return "AI_UPGRADE".equalsIgnoreCase(source);
    }
}
