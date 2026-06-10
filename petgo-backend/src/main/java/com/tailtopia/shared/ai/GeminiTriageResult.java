package com.tailtopia.shared.ai;

import java.util.Map;

/**
 * Gemini 分诊解析结果（Story 4.1）。shared/ai 不依赖 triage 模块——{@code dangerLevel} 以 String 出参
 * （{@code GREEN|YELLOW|RED}），由 triage 服务映射为领域枚举。
 *
 * <p>⚠️ {@code dangerLevel} 仅为<b>模型解析值</b>，不是最终裁决；triage 在写库前经后置安全规则层
 * （Story 4.2）<b>只升不降</b>地裁定最终级别。{@code raw} 为 Gemini 原始响应，落 JSONB 存档/审计。
 *
 * @param dangerLevel   模型解析出的级别字符串（GREEN/YELLOW/RED）
 * @param advice        观察 / 处理建议
 * @param medicationRef 用药参考（可空）
 * @param disclaimer    免责声明（NFR-9，前置展示在 4.4）
 * @param observation   黄色条件倒计时协议三要素（FR-2，可空；绿色通常为 null）
 * @param raw           Gemini 原始响应（JSONB 存档）
 */
public record GeminiTriageResult(
        String dangerLevel,
        String advice,
        String medicationRef,
        String disclaimer,
        TriageObservation observation,
        Map<String, Object> raw) {
}
