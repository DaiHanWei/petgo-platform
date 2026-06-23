package com.tailtopia.consult.dto;

import java.util.List;

/**
 * 发起咨询请求（Story 5.3 建，Story 5.4 扩展，Story F 增量）。
 *
 * <p>{@code source} 缺省 DIRECT（用户直接发起）；{@code AI_UPGRADE} 时须带 {@code triageTaskId}，
 * 后端经 triage service 拉评级/描述/图片组装上下文（前端不重传）。
 *
 * <p>Story F：DIRECT 直连问诊可带用户自填病例 —— {@code symptomText}（症状）+
 * {@code imageObjectKeys}（私密桶对象 key，前端已直传；后端不收签名 URL）。AI_UPGRADE 忽略这两个字段。
 */
public record CreateConsultSessionRequest(String source, Long triageTaskId,
        String symptomText, List<String> imageObjectKeys) {

    public boolean isAiUpgrade() {
        return "AI_UPGRADE".equalsIgnoreCase(source);
    }
}
