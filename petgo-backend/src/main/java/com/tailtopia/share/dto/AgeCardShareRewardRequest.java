package com.tailtopia.share.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 年龄卡分享上报的请求体（V1.3.0 批次 A · Story 5.3 · AC6）。
 *
 * <h2>🔴 只有幂等键，一个字段都不多</h2>
 * **不携带卡面内容、不上传图片**。「年龄卡不落服务端」指的是卡片图像；
 * 领奖是一次独立的服务端调用，这是已澄清的唯一例外 —— 而这个例外只包含
 * 「谁、哪次分享」，不包含「分享了什么」。
 *
 * <p>⚠️ 加字段前先回去读 AC6：卡面上有宠物名、生日推算出的年龄、头像 URL，
 * 任何一项进到这里都是把一次娱乐分享变成一次个人数据上报。
 */
public record AgeCardShareRewardRequest(
        @NotBlank @Size(max = 64) String idempotencyKey) {
}
