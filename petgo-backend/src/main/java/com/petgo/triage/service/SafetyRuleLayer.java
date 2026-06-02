package com.petgo.triage.service;

import com.petgo.triage.domain.DangerLevel;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 确定性安全规则层 —— 后置强制升红的<b>唯一挂载点</b>（Story 4.1 预留，<b>Story 4.2 填充</b>）。
 *
 * <p>🔒 安全攸关约束（不可协商，dev 实现 4.2 时 MUST 遵守）：
 * <ul>
 *   <li><b>唯一、显式、不可旁路</b>：强制升红只能发生在「Gemini 返回解析后、写库 DONE 前」这一处
 *       （{@code TriageProcessor} 在 markDone 前调用本方法）。禁止在别处分散决定 {@code danger_level}，
 *       防止 4.2 接入后存在绕过路径。</li>
 *   <li><b>只升不降</b>：4.2 实现 MUST 用 {@link DangerLevel#atLeast} 合并规则级别，
 *       绝不把模型级别降级。存疑即升红（决策 E5）。</li>
 * </ul>
 *
 * <p>本故事（4.1）为 <b>no-op 占位</b>：原样返回模型解析级别，不做任何升降。
 */
@Component
public class SafetyRuleLayer {

    /**
     * 后置裁决最终危险级别。
     *
     * @param modelLevel       模型解析出的级别（可能为 null）
     * @param symptomText      症状文字（4.2 用于关键词匹配）
     * @param imageObjectKeys  图片对象 key（4.2 可选用）
     * @return 最终级别。<b>4.1 占位实现原样返回 {@code modelLevel}</b>。
     */
    public DangerLevel enforce(DangerLevel modelLevel, String symptomText, List<String> imageObjectKeys) {
        // TODO(Story 4.2): 确定性高危关键词规则 → 强制升红（只升不降、不可绕过、保守否定处理 E5）。
        //   实现形如：DangerLevel ruleLevel = match(symptomText) ? RED : null;
        //            return ruleLevel == null ? modelLevel : DangerLevel.RED.atLeast(modelLevel);
        return modelLevel;
    }
}
