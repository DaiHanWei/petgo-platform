package com.tailtopia.gift.web.dto;

import java.util.List;

/**
 * 礼物页汇总 + 礼物墙（V1.4.0 gift Story 2.1 · AC1，{@code GET /api/v1/pets/{petId}/gifts}）。
 *
 * <p>只下发代码与数字；不下发 {@code owner_id} / 送礼人 id / 任何订单或目录 token（AC8.3）。
 *
 * @param viewerIsOwner  查看者是否主人（App 据此决定是否显示底部「送礼」）
 * @param totalValue     收到的礼物总价值（IDR，Σ unit_price × count，只含已送达）。主人与访客一致
 * @param giftCount      收到的礼物个数（Σ count）。主人与访客一致
 * @param supporterCount 送礼人数；<b>主人视角排除其隐藏的送礼人</b>（与粉丝榜行数一致，AC2.5），访客不排除
 * @param wall           礼物墙全部行（单价降序、同价数量降序）
 */
public record PetGiftShowcaseResponse(
        PetView pet,
        boolean viewerIsOwner,
        long totalValue,
        long giftCount,
        long supporterCount,
        List<GiftWallItemView> wall) {

    /**
     * 宠物头部。
     *
     * @param avatarUrl 已去 EXIF 的分发地址（与访客视图同口径）；无头像为 null
     * @param petType   {@code CAT} / {@code DOG} / {@code OTHER}
     */
    public record PetView(long petId, String name, String avatarUrl, String petType) {
    }
}
