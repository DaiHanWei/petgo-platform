package com.tailtopia.share.dto;

/**
 * 身份证卡面分享上报的响应（V1.1.6 Story 18.2）。
 *
 * <p>{@code coins} = 这次真的发了多少枚；{@code 0} = 没发。
 * ⚠️ <b>刻意不返回原因</b>（AC3/AC6）：档案已拿过 / 日上限 / 月度上限 / 总开关关闭
 * 对客户端是同一件事 —— 只是"不展示 +N 轻提示"。
 * 🔴 返回原因就会有人把它做成「你的额度用完了」的文案，而那会诱导
 * 「攒着别分享」或「月初集中刷满」。
 */
public record IdCardShareRewardResponse(long coins) {

    public static IdCardShareRewardResponse of(long coins) {
        return new IdCardShareRewardResponse(coins);
    }
}
