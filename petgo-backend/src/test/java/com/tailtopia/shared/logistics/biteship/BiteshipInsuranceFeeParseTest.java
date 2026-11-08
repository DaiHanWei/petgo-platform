package com.tailtopia.shared.logistics.biteship;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** L0（V1.4.2 shop-admin Story 6.1 · G17）：下单响应 {@code courier.insurance.fee} 解析；缺失按 null（调用方记 0 + WARN）。 */
class BiteshipInsuranceFeeParseTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void parsesFeeAndRoundsUp() {
        var root = JSON.readTree("{\"id\":\"bs_1\",\"courier\":{\"insurance\":{\"fee\":700.2}}}");
        assertThat(LiveBiteshipClient.snapshot(root).insuranceFee()).isEqualTo(701L);
    }

    @Test
    void missingFeeIsNull() {
        var root = JSON.readTree("{\"id\":\"bs_1\",\"courier\":{\"company\":\"jne\"}}");
        assertThat(LiveBiteshipClient.snapshot(root).insuranceFee()).isNull();
    }
}
