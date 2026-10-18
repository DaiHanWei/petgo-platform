package com.tailtopia.triage.dto;

import com.tailtopia.triage.domain.DangerLevel;
import java.util.List;

/**
 * 分诊升级兽医的上下文快照（Story 5.4，triage→consult 跨模块经 service 接口传递）。
 *
 * <p>仅暴露升级所需字段（评级 + 症状 + 私密图对象 key）；consult 据此定格快照。
 * {@code dangerLevel} 由 consult 侧做红线兜底（RED 拒绝升级）。
 * {@code petId}（V1.4.0 multi-pet Story 4.1 · AD-2）= 该分诊任务所选宠物（{@code triage_tasks.pet_id}，可空、无外键，
 * 可能已删）；consult 升级入队据此定宠物，归属由 consult 侧再校验。
 */
public record TriageUpgradeContext(
        long triageTaskId,
        DangerLevel dangerLevel,
        String symptomText,
        List<String> imageObjectKeys,
        Long petId) {
}
