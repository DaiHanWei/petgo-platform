package com.tailtopia.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** L0（V1.3.4 ktp Story 2.1，AD-2）：HD 解锁时刻只在首次解锁时写入。 */
class IdCardTest {

    private static IdCard card() {
        return IdCard.snapshot(1L, 1L, "Momo", "CAT", null, null, null, null, "FEMALE", null, null,
                null, null, null, null, "STUDENT", "BINTANG", null, null);
    }

    @Test
    void newSnapshotHasNoUnlockTime() {
        IdCard c = card();
        assertThat(c.isHdUnlocked()).isFalse();
        assertThat(c.getHdUnlockedAt()).isNull();
    }

    @Test
    void firstUnlockWritesTimeWithinWindow() {
        IdCard c = card();
        Instant before = Instant.now();
        c.markHdUnlocked();
        Instant after = Instant.now();
        assertThat(c.isHdUnlocked()).isTrue();
        assertThat(c.getHdUnlockedAt()).isBetween(before, after);
    }

    @Test
    void secondUnlockKeepsFirstTime() throws Exception {
        IdCard c = card();
        c.markHdUnlocked();
        Instant first = c.getHdUnlockedAt();
        Thread.sleep(5);
        c.markHdUnlocked();
        assertThat(c.getHdUnlockedAt()).isEqualTo(first);
        assertThat(c.isHdUnlocked()).isTrue();
    }
}
