package com.tailtopia.avatarmoderation.event;

import com.tailtopia.avatarmoderation.domain.AvatarSubjectType;

/**
 * 头像更换/上传领域事件（内容审核 story 5，过去式）。头像已「先放行」立即生效并落库后发布，
 * 由 {@code avatarmoderation} 模块 {@code @Async @TransactionalEventListener(AFTER_COMMIT)} 消费送审——
 * 触发方（auth/profile）不直调 avatarmoderation service，经事件解耦（与名称侧 {@code NameSubmittedEvent} 同构）。
 *
 * <p><b>D-CM2 可见窗口期（有意权衡）</b>：头像先放行、异步审核完成前对所有人可见，由异步 + 举报兜底。
 * 送审仅在头像实际变化且<b>非平台默认常量</b>时触发（重置为默认本身不再送审，防自审循环 B12）。
 *
 * @param subjectType USER_AVATAR / PET_AVATAR
 * @param subjectId   USER_AVATAR=users.id；PET_AVATAR=pet_profiles.id（内部值，绝不外露）
 * @param avatarUrl   送审头像 URL（版本键；下游只写入 avatar_reviews，禁入日志）
 */
public record AvatarReviewRequestedEvent(AvatarSubjectType subjectType, long subjectId, String avatarUrl) {
}
