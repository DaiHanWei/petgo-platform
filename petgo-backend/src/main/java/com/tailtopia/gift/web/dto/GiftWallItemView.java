package com.tailtopia.gift.web.dto;

import com.tailtopia.gift.service.GiftShowcaseQuery.WallRow;

/**
 * 礼物墙一行（V1.4.0 gift Story 2.1 · AC1.2）：礼物名 EN / ID 两份快照原样下发（App 按语言取），不下发拼好的显示串，
 * 也不下发 {@code gift_id} / 目录 {@code public_token}。同一礼物改价后按价位分成两行。
 *
 * @param unitPrice 成交单价（IDR，订单快照）
 * @param count     收到的个数
 */
public record GiftWallItemView(String nameEn, String nameId, String iconUrl, long unitPrice, long count) {

    public static GiftWallItemView of(WallRow r) {
        return new GiftWallItemView(r.nameEn(), r.nameId(), r.iconUrl(), r.unitPrice(), r.count());
    }
}
