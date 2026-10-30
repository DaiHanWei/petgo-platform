package com.tailtopia.merchant.po.service;

/**
 * 进货单支付意图的付款人标识（V1.4.1）。
 *
 * <p>{@code payment_intents.user_id} NOT NULL、到账事件 {@code PaymentIntentPaidEvent.userId} 是 {@code long}，
 * 而进货单的付款人是<b>门店</b>不是 App 用户。存 {@code -merchant_store_id}：
 * <ul>
 *   <li>负数与任何 {@code users.id} 都不相交 —— 不会把一笔进货款记到某个 App 用户头上；</li>
 *   <li>限流键 {@code rl:pay:create:<userId>} 按门店隔离，不会所有门店挤一个桶；</li>
 *   <li>后台支付列表按 user_id 查不到用户，显示为空 —— 进货单要看 {@code purpose = MERCHANT_PO} 去进货单页对账。</li>
 * </ul>
 * 成长值另有两道排除（{@code growth_spend_tiers} NONE + locked、{@code GrowthSpendListener}）。
 */
public final class PurchaseOrderPayer {

    private PurchaseOrderPayer() {
    }

    public static long intentUserIdFor(long merchantStoreId) {
        if (merchantStoreId <= 0) {
            throw new IllegalArgumentException("merchantStoreId must be positive");
        }
        return -merchantStoreId;
    }
}
