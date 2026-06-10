package com.petgo.triage.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** L0：只升不降语义（4.2 升红地基）+ 宽松解析。 */
class DangerLevelTest {

    @Test
    void atLeastNeverDowngrades() {
        assertThat(DangerLevel.GREEN.atLeast(DangerLevel.RED)).isEqualTo(DangerLevel.RED);
        assertThat(DangerLevel.RED.atLeast(DangerLevel.GREEN)).isEqualTo(DangerLevel.RED);
        assertThat(DangerLevel.YELLOW.atLeast(DangerLevel.GREEN)).isEqualTo(DangerLevel.YELLOW);
        assertThat(DangerLevel.GREEN.atLeast(null)).isEqualTo(DangerLevel.GREEN);
    }

    @Test
    void fromNullableParsesLeniently() {
        assertThat(DangerLevel.fromNullable("red")).isEqualTo(DangerLevel.RED);
        assertThat(DangerLevel.fromNullable(" YELLOW ")).isEqualTo(DangerLevel.YELLOW);
        assertThat(DangerLevel.fromNullable(null)).isNull();
        assertThat(DangerLevel.fromNullable("???")).isNull();
    }
}
