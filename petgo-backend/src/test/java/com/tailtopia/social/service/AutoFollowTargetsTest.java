package com.tailtopia.social.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** L0：自动关注目标配置解析（V1.4.1，env AUTO_FOLLOW_USER_IDS）。 */
class AutoFollowTargetsTest {

    @Test
    void emptyOrBlankMeansNoTarget() {
        assertThat(new AutoFollowTargets("").ids()).isEmpty();
        assertThat(new AutoFollowTargets("  ").ids()).isEmpty();
        assertThat(new AutoFollowTargets(null).ids()).isEmpty();
    }

    @Test
    void commaSeparatedTrimmedDeduplicatedInOrder() {
        AutoFollowTargets t = new AutoFollowTargets(" 81, 7 ,81,,");
        assertThat(t.ids()).containsExactly(81L, 7L);
        assertThat(t.contains(7)).isTrue();
        assertThat(t.contains(32)).isFalse();
    }

    @Test
    void malformedFailsFast() {
        assertThatThrownBy(() -> new AutoFollowTargets("81,tailus")).isInstanceOf(NumberFormatException.class);
    }
}
