package com.tailtopia.growth.service;

import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.domain.GrowthTier;
import java.util.EnumSet;
import java.util.Set;

/**
 * 头像框<b>读时推导</b>（V1.4.0 ① Story 1.2 · 架构 AD-10 第三点、PRD AC19）。纯函数，无 IO。
 *
 * <p>不存「当前框」：可用档由段位决定（BUDDY → BASIC，GUARDIAN → +UPGRADED，STAR → +ANIMATED）。
 * {@code frame_choice = null}（自动）→ 最高可用档（NEWBIE → {@link FrameChoice#NONE}）；{@code NONE} → 不戴；
 * 已选且仍可用 → 它；已选但因降级不可用 → 最高可用档。降级<b>不改写</b> {@code frame_choice}，重新升回时恢复原选择。
 */
public final class GrowthFrameResolver {

    private GrowthFrameResolver() {
    }

    /** 该段位可用的头像框（不含恒可选的 {@link FrameChoice#NONE}）。 */
    public static Set<FrameChoice> availableFrames(GrowthTier tier) {
        if (tier == null) {
            return EnumSet.noneOf(FrameChoice.class);
        }
        return switch (tier) {
            case NEWBIE -> EnumSet.noneOf(FrameChoice.class);
            case BUDDY -> EnumSet.of(FrameChoice.BASIC);
            case GUARDIAN -> EnumSet.of(FrameChoice.BASIC, FrameChoice.UPGRADED);
            case STAR -> EnumSet.of(FrameChoice.BASIC, FrameChoice.UPGRADED, FrameChoice.ANIMATED);
        };
    }

    /** 生效头像框。 */
    public static FrameChoice effectiveFrame(GrowthTier tier, FrameChoice frameChoice) {
        if (frameChoice == FrameChoice.NONE) {
            return FrameChoice.NONE;
        }
        Set<FrameChoice> available = availableFrames(tier);
        if (frameChoice != null && available.contains(frameChoice)) {
            return frameChoice;
        }
        return highest(available);
    }

    /** 是否手动选过（非 null 即手动，含主动选「不戴」）。 */
    public static boolean isManual(FrameChoice frameChoice) {
        return frameChoice != null;
    }

    private static FrameChoice highest(Set<FrameChoice> available) {
        if (available.contains(FrameChoice.ANIMATED)) {
            return FrameChoice.ANIMATED;
        }
        if (available.contains(FrameChoice.UPGRADED)) {
            return FrameChoice.UPGRADED;
        }
        if (available.contains(FrameChoice.BASIC)) {
            return FrameChoice.BASIC;
        }
        return FrameChoice.NONE;
    }
}
