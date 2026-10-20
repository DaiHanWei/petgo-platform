package com.tailtopia.growth.web.dto;

import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.domain.GrowthTier;

/**
 * {@code /me} 的成长值摘要（V1.4.0 ① Story 1.4 · AD-14）：「我的」页等级胶囊用。不参与者整个对象为 null。
 *
 * @param level         当前等级
 * @param tier          段位
 * @param frame         生效头像框
 * @param lifetimeTotal 累计 EXP
 * @param nextThreshold 下一级门槛；满级为 null
 */
public record MeGrowthBrief(int level, GrowthTier tier, FrameChoice frame, long lifetimeTotal, Long nextThreshold) {
}
