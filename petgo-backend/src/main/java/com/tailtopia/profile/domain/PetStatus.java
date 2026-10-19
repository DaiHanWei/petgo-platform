package com.tailtopia.profile.domain;

/**
 * 宠物状态（V1.4.0 multi-pet Story 2.1 · AD-4）。落库 {@code pet_profiles.status}（varchar(16)，UPPER_SNAKE）。
 *
 * <p>只经 {@code PetSlotStateService.reconcile} 改（架构 §5 单一入口）；可写校验只经 {@code PetWriteGuard}。
 */
public enum PetStatus {
    /** 正常：可新增记录 / 编辑 / 按宠物付费。 */
    ACTIVE,
    /** 只读：超出名额，可查看 / 分享 / 收礼 / 删除，不可新增或修改记录。 */
    READ_ONLY
}
