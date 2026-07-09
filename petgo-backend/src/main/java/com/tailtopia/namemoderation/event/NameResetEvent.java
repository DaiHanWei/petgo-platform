package com.tailtopia.namemoderation.event;

import com.tailtopia.namemoderation.domain.NameTargetType;

/**
 * 名称被违规重置领域事件（内容审核 story 4，过去式，负向结果 → 推送 D-CM6）。运营判 VIOLATION、名称已重置为
 * 系统默认编码名后发布，供 notify 模块推送 {@code NAME_RESET} 通知（§5.6）+ story 9 违规计数订阅（本 story 不建计数表）。
 *
 * <p>推送目标与 {@code targetRef} 在重置时解析好放入事件（notify 侧无需回查 users/pet_profiles）：
 * 昵称 → {@code recipientUserId}=users.id、{@code targetRef}="NICKNAME"（跳「设置昵称」页）；
 * 宠物名 → {@code recipientUserId}=owner user id、{@code targetRef}=pet cardToken（跳该宠物改名页）。
 *
 * @param targetType      NICKNAME / PET_NAME
 * @param recipientUserId 通知接收者 users.id
 * @param targetRef       客户端跳转定位串（昵称固定 "NICKNAME"；宠物名为不可枚举 cardToken）
 */
public record NameResetEvent(NameTargetType targetType, long recipientUserId, String targetRef) {
}
