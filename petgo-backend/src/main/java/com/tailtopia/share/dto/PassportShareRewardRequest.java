package com.tailtopia.share.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 护照 / 登机牌卡分享上报（V1.3.2 Story 4.5 · AC3.1）。
 *
 * <p>🔴 <b>只有卡类型</b>：不收场所 token（登机牌整体一个类型，按张计会让「打卡数 = 奖励数」）、
 * 不收快照 token、不收水印态。非法值 → 422。
 */
public record PassportShareRewardRequest(
        @NotBlank @Pattern(regexp = "PAGE|BOARDING") String cardType) {
}
