package com.tailtopia.profile.slot;

/**
 * 运营补发宠物名额的入参（V1.4.0 multi-pet Story 4.3 · AB-34.2）：由后台 {@code AdminPetSlotGrantService} 组装，
 * 交 {@link PetSlotPurchaseService#grantByAdmin} 执行。校验在 {@code grantByAdmin} 里做（422），本记录不校验。
 *
 * @param userId          目标用户
 * @param days            补发天数（1–365）
 * @param reason          补发原因（必填，≤ 200；只进购买行与审计，不进日志）
 * @param grantToken      页签渲染时生成的一次性幂等 token（{@code idem_key = "ADMIN_GRANT:" + token}）
 * @param operatorAdminId 操作人（{@code admin_accounts.id}）
 */
public record AdminSlotGrantCommand(long userId, int days, String reason, String grantToken, long operatorAdminId) {
}
