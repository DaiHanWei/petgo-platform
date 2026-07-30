package com.tailtopia.pay.refund.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * L0：出款渠道费权威值（Story 4.3，net=order−fee 的 fee 源）。BCA0/OVO2500/GoPay2500。
 */
class PayoutChannelTest {

    @Test
    void channelFees() {
        assertThat(PayoutChannel.BCA.fee()).isZero();
        assertThat(PayoutChannel.OVO.fee()).isEqualTo(2500);
        assertThat(PayoutChannel.GOPAY.fee()).isEqualTo(2500);
    }
}
