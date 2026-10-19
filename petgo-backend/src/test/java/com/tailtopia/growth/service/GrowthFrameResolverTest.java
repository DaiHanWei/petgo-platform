package com.tailtopia.growth.service;

import static com.tailtopia.growth.domain.FrameChoice.ANIMATED;
import static com.tailtopia.growth.domain.FrameChoice.BASIC;
import static com.tailtopia.growth.domain.FrameChoice.NONE;
import static com.tailtopia.growth.domain.FrameChoice.UPGRADED;
import static com.tailtopia.growth.domain.GrowthTier.BUDDY;
import static com.tailtopia.growth.domain.GrowthTier.GUARDIAN;
import static com.tailtopia.growth.domain.GrowthTier.NEWBIE;
import static com.tailtopia.growth.domain.GrowthTier.STAR;
import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.domain.GrowthTier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** V1.4.0 ① Story 1.2 · AC6（L0）：四段位 × 五种 frameChoice 全组合（PRD AC19）。 */
class GrowthFrameResolverTest {

    static Stream<Arguments> table() {
        return Stream.of(
                // tier, choice, effective
                Arguments.of(NEWBIE, null, NONE),
                Arguments.of(NEWBIE, NONE, NONE),
                Arguments.of(NEWBIE, BASIC, NONE),
                Arguments.of(NEWBIE, UPGRADED, NONE),
                Arguments.of(NEWBIE, ANIMATED, NONE),
                Arguments.of(BUDDY, null, BASIC),
                Arguments.of(BUDDY, NONE, NONE),
                Arguments.of(BUDDY, BASIC, BASIC),
                Arguments.of(BUDDY, UPGRADED, BASIC), // GUARDIAN 手选 UPGRADED → 降到 BUDDY 得 BASIC（AC19）
                Arguments.of(BUDDY, ANIMATED, BASIC),
                Arguments.of(GUARDIAN, null, UPGRADED),
                Arguments.of(GUARDIAN, NONE, NONE),
                Arguments.of(GUARDIAN, BASIC, BASIC),
                Arguments.of(GUARDIAN, UPGRADED, UPGRADED),
                Arguments.of(GUARDIAN, ANIMATED, UPGRADED),
                Arguments.of(STAR, null, ANIMATED),
                Arguments.of(STAR, NONE, NONE), // 手选不戴，升级后仍不戴
                Arguments.of(STAR, BASIC, BASIC),
                Arguments.of(STAR, UPGRADED, UPGRADED),
                Arguments.of(STAR, ANIMATED, ANIMATED));
    }

    @ParameterizedTest(name = "{0} + {1} → {2}")
    @MethodSource("table")
    void effectiveFrame(GrowthTier tier, FrameChoice choice, FrameChoice expected) {
        assertThat(GrowthFrameResolver.effectiveFrame(tier, choice)).isEqualTo(expected);
    }

    @Test
    void availableFramesByTier() {
        assertThat(GrowthFrameResolver.availableFrames(NEWBIE)).isEmpty();
        assertThat(GrowthFrameResolver.availableFrames(BUDDY)).containsExactly(BASIC);
        assertThat(GrowthFrameResolver.availableFrames(GUARDIAN)).containsExactly(BASIC, UPGRADED);
        assertThat(GrowthFrameResolver.availableFrames(STAR)).containsExactly(BASIC, UPGRADED, ANIMATED);
    }

    @Test
    void manualMeansAnyNonNullChoice() {
        assertThat(GrowthFrameResolver.isManual(null)).isFalse();
        assertThat(GrowthFrameResolver.isManual(NONE)).isTrue();
        assertThat(GrowthFrameResolver.isManual(UPGRADED)).isTrue();
    }
}
