package com.tailtopia.gift.dto;

import com.tailtopia.gift.domain.GiftOrder;

/**
 * 单笔送礼订单状态（V1.4.0 gift Story 1.1 · AC9.2）：{@code GET /api/v1/me/gift-orders/{orderToken}}，App QRIS
 * {@code pollPaid} 用。只读订单，不碰支付意图。不含 {@code petId} 以外的内部 id。
 *
 * @param petId 收礼宠物 id（与访客路由一致；宠物已删除为 null）
 */
public record GiftOrderStatusResponse(String orderToken, String status, Long petId, String petName,
        String giftNameEn, String giftNameId, int quantity, long total, String channel) {

    public static GiftOrderStatusResponse of(GiftOrder o) {
        return new GiftOrderStatusResponse(o.getPublicToken(), o.getStatus().name(), o.getPetId(), o.getPetName(),
                o.getGiftNameEn(), o.getGiftNameId(), o.getQuantity(), o.getTotal(), o.getChannel().name());
    }
}
