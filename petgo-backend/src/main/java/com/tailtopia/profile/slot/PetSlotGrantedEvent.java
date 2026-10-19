package com.tailtopia.profile.slot;

/**
 * QRIS 到账已发放月租名额（V1.4.0 multi-pet Story 2.2 · code review #1）：{@code PetSlotPaidHandler} 在到账事务内发布，
 * {@link PetSlotGrantedReconcileListener} 在<b>提交之后</b>另开事务 reconcile —— reconcile 失败不会回滚到账。
 *
 * @param userId  名额所属用户
 * @param trigger RENTAL_ADDED / RENEWED（只恢复不降级，延后执行安全）
 */
public record PetSlotGrantedEvent(long userId, PetSlotTrigger trigger) {
}
