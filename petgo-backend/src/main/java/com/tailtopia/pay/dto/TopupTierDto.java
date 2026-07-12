package com.tailtopia.pay.dto;

import com.tailtopia.pay.domain.TopupTier;

/**
 * 充值档位（Story 1.5 前端渲染用）。{@code id}=对外档位 id（"10k".."100k"）、{@code amount}=充值金额
 * （IDR 最小单位）、{@code coins}=到账 koin（1:1）。
 *
 * @param id     档位对外 id
 * @param amount 充值金额（IDR）
 * @param coins  到账 koin
 */
public record TopupTierDto(String id, long amount, long coins) {

    public static TopupTierDto from(TopupTier t) {
        return new TopupTierDto(t.getId(), t.getAmountIdr(), t.getCoins());
    }
}
