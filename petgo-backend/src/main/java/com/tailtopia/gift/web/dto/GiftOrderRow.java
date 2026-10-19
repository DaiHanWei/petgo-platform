package com.tailtopia.gift.web.dto;

import java.time.Instant;

/**
 * 我的送礼记录一行（V1.4.0 gift Story 2.3 · AC1.4，{@code GET /api/v1/me/gift-orders}）。
 *
 * <p>只下发对外标识 {@code orderToken}（{@code gift_orders.public_token}）与宠物路由用的 {@code petId}；<b>不下发</b>
 * 自增 id、{@code sender_id} / {@code owner_id} / {@code gift_id} / {@code payment_intent_id} / {@code idem_key} /
 * {@code undelivered_reason} / {@code refund_error}，也不下发 {@code pet_name} 快照（宠物已删行 App 只显示「该宠物已不存在」）。
 *
 * @param orderToken 订单对外 token
 * @param createdAt  下单时刻（UTC）
 * @param status     订单状态代码原样（{@code GiftOrderStatus} 名），App 按代码本地化
 * @param channel    {@code PAWCOIN} / {@code QRIS}
 * @param quantity   数量
 * @param total      总额（IDR）
 * @param gift       礼物快照（订单列，不 join 目录取现值）
 * @param pet        收礼宠物当下的名字与头像；宠物已删 → null（NON_NULL 省略）
 * @param petGone    宠物是否已不存在
 */
public record GiftOrderRow(
        String orderToken,
        Instant createdAt,
        String status,
        String channel,
        int quantity,
        long total,
        GiftView gift,
        PetView pet,
        boolean petGone) {

    /** 礼物快照（下单时的名称 EN / ID、图标、单价）。 */
    public record GiftView(String nameEn, String nameId, String iconUrl, long unitPrice) {
    }

    /** 收礼宠物（经 {@code GiftPetDirectory} 取当下值；头像为 EXIF 剥离后的投递 URL）。 */
    public record PetView(long petId, String name, String avatarUrl) {
    }
}
