package com.tailtopia.content.autolike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** L0 · V1.5.0 likes Story 2.1 AC1：默认值与启动校验。 */
class AutoLikePropertiesTest {

    @Test
    void defaults() {
        AutoLikeProperties p = new AutoLikeProperties();
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getMorningCron()).isEqualTo("0 30 10 * * *");
        assertThat(p.getEveningCron()).isEqualTo("0 30 16 * * *");
        assertThat(p.getCheckWindowMin()).isEqualTo(Duration.ofHours(36));
        assertThat(p.getCheckWindowMax()).isEqualTo(Duration.ofHours(60));
        assertThat(p.getMaxPostAge()).isEqualTo(Duration.ofDays(7));
        assertThat(p.getLikeTarget()).isEqualTo(5);
        assertThat(p.getLikesPerRun()).isEqualTo(1);
        assertThat(p.getMaxLikesPerAuthorPerRun()).isEqualTo(1);
        assertThat(p.getSpreadWindow()).isEqualTo(Duration.ofHours(3));
        assertThat(p.getHoldoutPercent()).isEqualTo(10);
        assertThat(p.getHoldoutSalt()).isEqualTo("tt-auto-like-holdout-v1");
        assertThat(p.getMaxFailures()).isEqualTo(3);
        assertThat(p.getStopAfterConsecutiveFailures()).isEqualTo(10);
        assertThat(p.getDispatchIntervalMs()).isEqualTo(60_000L);
        assertThat(p.getDispatchBatch()).isEqualTo(200);
        assertThatCode(p::validate).doesNotThrowAnyException();
    }

    @Test
    void likesPerRunOtherThanOneFailsStartup() {
        AutoLikeProperties p = new AutoLikeProperties();
        p.setLikesPerRun(2);
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("likes-per-run 目前只支持 1");
    }

    @Test
    void invertedWindowFailsStartup() {
        AutoLikeProperties p = new AutoLikeProperties();
        p.setCheckWindowMin(Duration.ofHours(60));
        p.setCheckWindowMax(Duration.ofHours(36));
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class);

        AutoLikeProperties q = new AutoLikeProperties();
        q.setCheckWindowMax(Duration.ofDays(8)); // > maxPostAge
        assertThatThrownBy(q::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void otherBoundsFailStartup() {
        AutoLikeProperties a = new AutoLikeProperties();
        a.setHoldoutPercent(101);
        assertThatThrownBy(a::validate).isInstanceOf(IllegalStateException.class);
        AutoLikeProperties b = new AutoLikeProperties();
        b.setHoldoutPercent(-1);
        assertThatThrownBy(b::validate).isInstanceOf(IllegalStateException.class);
        AutoLikeProperties c = new AutoLikeProperties();
        c.setLikeTarget(0);
        assertThatThrownBy(c::validate).isInstanceOf(IllegalStateException.class);
        AutoLikeProperties d = new AutoLikeProperties();
        d.setSpreadWindow(Duration.ZERO);
        assertThatThrownBy(d::validate).isInstanceOf(IllegalStateException.class);
        AutoLikeProperties e = new AutoLikeProperties();
        e.setMaxFailures(0);
        assertThatThrownBy(e::validate).isInstanceOf(IllegalStateException.class);
        AutoLikeProperties f = new AutoLikeProperties();
        f.setMaxLikesPerAuthorPerRun(0);
        assertThatThrownBy(f::validate).isInstanceOf(IllegalStateException.class);
        AutoLikeProperties g = new AutoLikeProperties();
        g.setCheckWindowMin(Duration.ZERO);
        assertThatThrownBy(g::validate).isInstanceOf(IllegalStateException.class);
        AutoLikeProperties h = new AutoLikeProperties();
        h.setHoldoutSalt(" ");
        assertThatThrownBy(h::validate).isInstanceOf(IllegalStateException.class);
    }
}
