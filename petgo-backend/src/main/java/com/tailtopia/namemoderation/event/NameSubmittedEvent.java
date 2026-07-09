package com.tailtopia.namemoderation.event;

import com.tailtopia.namemoderation.domain.NameTargetType;

/**
 * 名称提交/编辑领域事件（内容审核 story 4，过去式）。名称已「先放行」立即生效并落库后发布，
 * 由 {@code namemoderation} 模块 {@code @Async @TransactionalEventListener(AFTER_COMMIT)} 消费送审——
 * 触发方（auth/profile）不直调 namemoderation service，经事件解耦。
 *
 * @param targetType   NICKNAME / PET_NAME
 * @param targetRefId  昵称=users.id；宠物名=pet_profiles.id（内部值）
 * @param value        送审名称原文（审核证据；下游只写入 name_moderation_records，禁入日志）
 */
public record NameSubmittedEvent(NameTargetType targetType, long targetRefId, String value) {
}
