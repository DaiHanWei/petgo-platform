package com.tailtopia.content.autolike;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** L0 · V1.5.0 likes Story 2.1 AC3：对照组哈希（FR-223、A-AC48）。 */
class AutoLikeHoldoutTest {

    private static final String SALT = "tt-auto-like-holdout-v1";

    @Test
    void sameIdIsStable() {
        for (long id = 1; id <= 200; id++) {
            boolean first = AutoLikeHoldout.isHoldout(SALT, 10, id);
            for (int i = 0; i < 3; i++) {
                assertThat(AutoLikeHoldout.isHoldout(SALT, 10, id)).isEqualTo(first);
            }
        }
    }

    @Test
    void differentSaltChangesAssignment() {
        int diff = 0;
        for (long id = 1; id <= 10_000; id++) {
            if (AutoLikeHoldout.isHoldout(SALT, 10, id) != AutoLikeHoldout.isHoldout("other-salt", 10, id)) {
                diff++;
            }
        }
        assertThat(diff).isGreaterThan(500);
    }

    @Test
    void proportionIsAboutTenPercent() {
        int n = 100_000;
        int hits = 0;
        for (long id = 1; id <= n; id++) {
            if (AutoLikeHoldout.isHoldout(SALT, 10, id)) {
                hits++;
            }
        }
        double ratio = hits / (double) n;
        assertThat(ratio).isBetween(0.095, 0.105);
    }

    @Test
    void isNotTheSameAsIdEndingInZero() {
        int mismatch = 0;
        for (long id = 1; id <= 10_000; id++) {
            if (AutoLikeHoldout.isHoldout(SALT, 10, id) != (id % 10 == 0)) {
                mismatch++;
            }
        }
        assertThat(mismatch).isGreaterThan(500);
    }

    @Test
    void zeroAndHundredPercent() {
        for (long id = 1; id <= 1000; id++) {
            assertThat(AutoLikeHoldout.isHoldout(SALT, 0, id)).isFalse();
            assertThat(AutoLikeHoldout.isHoldout(SALT, 100, id)).isTrue();
        }
    }

    @Test
    void beanUsesConfiguredSaltAndPercent() {
        AutoLikeProperties p = new AutoLikeProperties();
        AutoLikeHoldout h = new AutoLikeHoldout(p);
        for (long id = 1; id <= 500; id++) {
            assertThat(h.isHoldout(id)).isEqualTo(AutoLikeHoldout.isHoldout(SALT, 10, id));
        }
    }
}
