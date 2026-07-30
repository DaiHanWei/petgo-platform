package com.tailtopia.profile.event;

import com.tailtopia.profile.domain.HealthRecordType;

/**
 * 结构化健康记录创建事件（Story 7.2，FR-45C 里程碑第四触发路径）。{@code HealthRecordService.create}
 * 提交后发布，{@code MilestoneAutoCompleteListener} 订阅按 {@code type} 自动完成对应里程碑
 * （VACCINE→M3 / DEWORM→M4，SYSTEM_AUTO，幂等）。健康数据 PII：事件只带 ownerId + type，不带内容明文。
 */
public record HealthRecordCreatedEvent(long ownerId, HealthRecordType type) {
}
