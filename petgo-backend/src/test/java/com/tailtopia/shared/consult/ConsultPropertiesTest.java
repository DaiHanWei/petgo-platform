package com.tailtopia.shared.consult;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * L0（无 DB/Redis）。Story 3.4 咨询计费配置默认值 + 兽医到手派生。
 */
class ConsultPropertiesTest {

    @Test
    void defaultsMatchArchitecture() {
        ConsultProperties p = new ConsultProperties();
        assertThat(p.getUnitPrice()).isEqualTo(50000L);   // Rp50,000
        assertThat(p.getVetShareRate()).isEqualTo(60);    // 60%
        assertThat(p.getMaxRebroadcast()).isEqualTo(5);   // 重播上限 5 次
        assertThat(p.getRequestMaxAgeMinutes()).isEqualTo(30); // 或存活 30min
    }

    @Test
    void vetPayoutIsUnitPriceTimesShare() {
        ConsultProperties p = new ConsultProperties();
        assertThat(p.vetPayout()).isEqualTo(30000L); // 50000 × 60 / 100 = 30000（架构 §3.2 例）

        p.setUnitPrice(40000L);
        p.setVetShareRate(50);
        assertThat(p.vetPayout()).isEqualTo(20000L); // 随配置变
    }
}
