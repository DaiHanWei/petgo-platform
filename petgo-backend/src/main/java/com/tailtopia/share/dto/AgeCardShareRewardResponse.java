package com.tailtopia.share.dto;

/**
 * 年龄卡分享上报的响应（V1.3.0 批次 A · Story 5.3）。
 *
 * <p>{@code coins} = 这次真的发了多少枚；{@code 0} = 没发。
 * ⚠️ <b>刻意不返回原因</b>（与身份证渠道同一口径）：去重命中 / 日上限 / 月度上限 /
 * 总开关关闭对客户端是同一件事 —— 只是"不展示 +N 轻提示"。
 * 🔴 返回原因就会有人把它做成「你的额度用完了」的文案，而那会诱导
 * 「攒着别分享」或「月初集中刷满」。
 */
public record AgeCardShareRewardResponse(long coins) {

    public static AgeCardShareRewardResponse of(long coins) {
        return new AgeCardShareRewardResponse(coins);
    }
}
