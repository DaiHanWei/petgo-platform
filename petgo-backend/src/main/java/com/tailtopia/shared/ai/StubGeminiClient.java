package com.petgo.shared.ai;

import java.util.List;
import java.util.Map;

/**
 * Gemini 打桩客户端（Story 4.1）。{@code petgo.ai.gemini.mode=stub}（默认）时装配，使分诊状态机 /
 * 重试 / 重扫 / 幂等在<b>无外部凭证</b>下可 L0/L1 验证（J1）。
 *
 * <p>返回固定 GREEN 结构化结果（最保守的"可正常对外"态；真正的高危升红由 4.2 后置层裁决，不靠桩）。
 * 真实 gemini-2.5-flash 端到端解析绿/黄/红属 L2（需真实 key），不在此桩覆盖。
 */
public class StubGeminiClient implements GeminiClient {

    @Override
    public GeminiTriageResult analyze(String symptomText, List<String> signedImageUrls) {
        Map<String, Object> raw = Map.of(
                "stub", true,
                "model", "stub-gemini",
                "note", "L0/L1 打桩响应：固定 GREEN，不打真实 Gemini");
        return new GeminiTriageResult(
                "GREEN",
                "症状描述未见明显急症信号，建议继续观察并保持记录。",
                null,
                "AI 分诊仅供参考，不替代专业兽医诊断；情况加重请尽快线下就医。",
                null, // 绿色无条件倒计时协议；黄色三要素由真实模型结构化产出（L2）
                raw);
    }
}
