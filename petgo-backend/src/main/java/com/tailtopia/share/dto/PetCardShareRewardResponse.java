package com.tailtopia.share.dto;

/**
 * Tailsonality / 护照两渠道分享上报的响应（V1.3.2 Story 4.5）：形状照 {@link AgeCardShareRewardResponse}。
 *
 * <p>{@code coins} = 这次真的发了多少枚；{@code 0} = 没发。⚠️ <b>刻意不返回原因</b>（已领过 / 无资格 /
 * 日上限 / 月度上限 / 总开关对客户端是同一件事 —— 不展示提示）：返回原因就会有人把它做成
 * 「你的额度用完了」的文案，诱导「攒着别分享」或「月初集中刷满」。
 */
public record PetCardShareRewardResponse(long coins) {

    public static PetCardShareRewardResponse of(long coins) {
        return new PetCardShareRewardResponse(coins);
    }
}
