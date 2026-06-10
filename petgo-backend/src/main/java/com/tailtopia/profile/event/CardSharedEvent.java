package com.tailtopia.profile.event;

import java.time.Instant;

/**
 * 宠物名片分享领域事件（Story 8.3，过去式）。名片分享在客户端经系统分享面板完成（无服务端分享动作），
 * 故由 App 在触发分享后回报一个轻量信号端点（{@code POST /pet-profiles/me/card-shares}）→ 发布本事件，
 * 驱动里程碑 C-S3「第一次分享宠物名片」自动完成（幂等，仅首次有效）。
 *
 * @param ownerId      档案所有者 user id
 * @param petProfileId 宠物档案 id
 * @param sharedAt     分享时间（UTC）
 */
public record CardSharedEvent(long ownerId, long petProfileId, Instant sharedAt) {
}
