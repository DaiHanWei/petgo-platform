package com.tailtopia.gift.web.dto;

import java.time.Instant;

/**
 * 逐笔礼物卡（V1.5.0 gift-pack Story 1-4 · 架构 §4.1 {@code GiftOrderCard}）。不下发送礼人 id / 主人 id / 目录 token / 订单自增 id。
 *
 * @param opened     是否已拆（<b>仅主人视角</b>；访客为 null，全局 non_null 下省略）
 * @param hasMessage 有未被隐藏的祝福语
 */
public record GiftOrderCard(String orderToken, String giftNameEn, String giftNameId, String giftIconUrl, int quantity,
        Instant receivedAt, Sender sender, Boolean opened, boolean hasMessage) {

    /** 送礼人（注销：nickname / avatarUrl 为 null，deleted = true）。 */
    public record Sender(String nickname, String avatarUrl, boolean deleted) {
    }
}
