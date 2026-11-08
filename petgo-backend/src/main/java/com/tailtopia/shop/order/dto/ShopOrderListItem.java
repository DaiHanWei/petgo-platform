package com.tailtopia.shop.order.dto;

import java.time.Instant;

/**
 * 电商订单列表卡（v1.4.2 shop Story 6.2 AC1.4，D-18 / F1 · F2）。null 字段按全局 NON_NULL 不下发。
 *
 * @param status               原始状态
 * @param displayStatus        展示状态（3.3 {@code displayStatusAt}，宽限期待支付 = {@code CANCELLED}）
 * @param totalAmount          订单应付总额（F1「列表金额统一为订单总额」）
 * @param itemCount            件数（同 {@code ShopOrderCardService} 口径，不是种类数）
 * @param paymentDeadline      仅 {@code displayStatus = PENDING_PAYMENT} 时 = {@code expires_at}
 * @param canPay               同详情口径（{@code ShopOrderPaymentService#canPay}）
 * @param logisticsStatusKey   最新活动 Biteship 运单的 {@code user_status_key}；手工运单 / 无运单为 null
 * @param deliveryIssue        = {@code ShopOrder#hasUserVisibleDeliveryIssue()}（5.2）
 * @param refundActionRequired 存在 {@code AWAITING_DESTINATION} 或 {@code PAYOUT_FAILED} 的退款单（4.1）
 */
public record ShopOrderListItem(
        String orderToken,
        String displayNo,
        String status,
        String displayStatus,
        long totalAmount,
        Instant createdAt,
        String thumbnailUrl,
        String itemTitle,
        Integer itemCount,
        Instant paymentDeadline,
        boolean canPay,
        String logisticsStatusKey,
        boolean deliveryIssue,
        boolean refundActionRequired) {
}
