package com.tailtopia.profile.slot;

/**
 * {@link PetSlotStateService#reconcile} 的触发原因（V1.4.0 multi-pet Story 2.1 · AD-4）。
 *
 * <p>只有 {@link #RENTAL_EXPIRED} 与 {@link #SELECTION_CHANGED} 会把宠物改成 {@code READ_ONLY}；其余只恢复不降级
 * （PRD：降级 / 名额减少时已建宠物照常用，只是不能新建）。
 */
public enum PetSlotTrigger {
    /** 月租名额到期（Story 2.3 调用）：唯一会自动降级的触发。 */
    RENTAL_EXPIRED,
    /** 新租月租名额（Story 2.2 调用）：只恢复。 */
    RENTAL_ADDED,
    /** 续租（Story 2.2 调用）：只恢复。 */
    RENEWED,
    /** Tailrent Level 变化（{@code GrowthLevelChangedEvent}）：只恢复，降级不转只读（AC9）。 */
    LEVEL_CHANGED,
    /** 用户在名额管理页改选（D13）：按 {@code keep_active} 重排，总正常数 ≤ 名额。 */
    SELECTION_CHANGED,
    /** 删除宠物释放名额（Story 2.1 AC6，对 AD-4 的补充）：只恢复。 */
    PET_DELETED
}
