package com.tailtopia.growth.domain;

/**
 * 作者等级<b>展示</b>投影（V1.4.0 ① Story 3.1 · AD-14 / 5.5）：头像角标 + 生效头像框。只用于展示，权益判定走
 * {@code GrowthQueryService#getLevel(s)}，两者不混用。
 *
 * @param level 展示等级（真实用户 = 落库等级；虚拟号 / 官方号 = 展示等级）
 * @param tier  段位（由等级表决定）
 * @param frame 生效头像框（{@link FrameChoice#BASIC} / {@link FrameChoice#UPGRADED} / {@link FrameChoice#ANIMATED}）；
 *              无框为 null，不放 {@link FrameChoice#NONE}
 */
public record GrowthBadge(int level, GrowthTier tier, FrameChoice frame) {
}
