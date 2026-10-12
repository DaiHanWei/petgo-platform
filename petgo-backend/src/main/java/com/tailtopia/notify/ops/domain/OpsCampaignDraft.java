package com.tailtopia.notify.ops.domain;

import com.tailtopia.ops.target.OpsTargetType;
import java.time.Instant;
import java.util.List;

/**
 * 新建运营通知活动的入参（V1.3.4 Story 6.2 · AC2）。admin 主题（AB-31）的新建接口组装后调
 * {@code OpsNotificationCampaignService.create}。
 *
 * @param targetType    跳转目标类型（Story 5.1 统一口径，校验走 {@code OpsTargetPolicy}）
 * @param audience      已由 {@link AudienceSpec#parse} 校验过的人群规格
 * @param targetUserIds 仅 IDS 模式：指定用户 id（可含无效 id，发送时跳过并计数）
 * @param scheduledAt   {@code null} = 立即发送
 */
public record OpsCampaignDraft(
        String titleId,
        String titleEn,
        String bodyId,
        String bodyEn,
        OpsTargetType targetType,
        String targetValue,
        AudienceSpec audience,
        List<Long> targetUserIds,
        Instant scheduledAt) {
}
