package com.tailtopia.gift.service;

import com.tailtopia.gift.dto.GiftOrderStatusResponse;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.shared.error.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 送礼订单只读查询（V1.4.0 gift Story 1.1 · AC9.2）。
 *
 * <p>单笔状态供 App QRIS {@code pollPaid} 轮询：<b>只读订单</b>，不碰支付意图（{@code PaymentIntentService.statusOf}
 * 的懒过期不发失败事件，这里不走它；过期由 {@code PaymentIntentExpiryScanner} 发事件驱动订单 EXPIRED）。
 */
@Service
public class GiftOrderQueryService {

    private final GiftOrderRepository orders;

    public GiftOrderQueryService(GiftOrderRepository orders) {
        this.orders = orders;
    }

    /** 仅本人：订单不存在或不是本人送出的一律 404（防枚举）。 */
    @Transactional(readOnly = true)
    public GiftOrderStatusResponse orderStatus(long senderId, String orderToken) {
        return orders.findByPublicToken(orderToken)
                .filter(o -> o.getSenderId() == senderId)
                .map(GiftOrderStatusResponse::of)
                .orElseThrow(() -> AppException.notFound("订单不存在"));
    }
}
