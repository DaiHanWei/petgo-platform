package com.tailtopia.consult.dto;

import java.util.List;

/**
 * 会话 AI 上下文（Story 5.4，兽医待接单预览 + 接单后顶部上下文卡用）。
 *
 * <p>{@code hasAiContext=false}（DIRECT 发起）时其余字段为空，前端不渲染上下文卡。
 * {@code imageUrls} 为私密桶短 TTL 签名 URL（现签，绝不入库/落日志）。
 */
public record ConsultAiContextResponse(
        boolean hasAiContext,
        String dangerLevel,
        String symptomText,
        List<String> imageUrls) {

    public static ConsultAiContextResponse empty() {
        return new ConsultAiContextResponse(false, null, null, List.of());
    }
}
