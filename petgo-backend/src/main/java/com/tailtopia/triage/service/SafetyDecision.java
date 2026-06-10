package com.petgo.triage.service;

import com.petgo.triage.domain.DangerLevel;
import java.util.List;

/**
 * 安全规则层后置裁决结果（Story 4.2）。
 *
 * @param finalLevel             最终危险级别（只升不降后的值，落库权威值）
 * @param escalatedBySafetyRule  是否因规则层命中而升级（模型本已 RED 则为 false）
 * @param matchedRuleIds         命中的高危急症 id 列表（仅 id，**不含症状健康数据**，供审计）
 */
public record SafetyDecision(
        DangerLevel finalLevel,
        boolean escalatedBySafetyRule,
        List<String> matchedRuleIds) {
}
