package com.tailtopia.avatarmoderation.event;

import com.tailtopia.avatarmoderation.domain.AvatarSubjectType;

/**
 * 头像被违规重置领域事件（内容审核 story 5，过去式，负向结果 → 推送 D-CM6）。运营判 VIOLATION、头像已重置为
 * 平台默认头像常量后发布，供 notify 模块推送 {@code AVATAR_RESET} 通知（§5.5）。
 *
 * <p>推送目标与 {@code targetRef} 在重置时解析好放入事件（notify 侧无需回查 users/pet_profiles）：
 * 用户头像 → {@code recipientUserId}=users.id、{@code targetRef}="USER_AVATAR"（跳我的页换头像入口）；
 * 宠物头像 → {@code recipientUserId}=owner user id、{@code targetRef}=pet cardToken（跳该宠物档案编辑页换头像）。
 *
 * @param subjectType     USER_AVATAR / PET_AVATAR
 * @param recipientUserId 通知接收者 users.id
 * @param targetRef       客户端跳转定位串（用户头像固定 "USER_AVATAR"；宠物头像为不可枚举 cardToken）
 */
public record AvatarResetEvent(AvatarSubjectType subjectType, long recipientUserId, String targetRef) {
}
