package com.tailtopia.triage.dto;

import com.tailtopia.triage.domain.DangerLevel;
import java.util.List;

/**
 * 分诊升级兽医的上下文快照（Story 5.4，triage→consult 跨模块经 service 接口传递）。
 *
 * <p>仅暴露升级所需字段（评级 + 症状 + 私密图对象 key）；consult 据此定格快照。
 * {@code dangerLevel} 由 consult 侧做红线兜底（RED 拒绝升级）。
 */
public record TriageUpgradeContext(
        long triageTaskId,
        DangerLevel dangerLevel,
        String symptomText,
        List<String> imageObjectKeys) {
}
