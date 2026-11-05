package com.tailtopia.shared.pay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 3.3 AC5.4 —— 桩查单恒回「已过期未付」，stub 环境订单能按时关。 */
class StubPaymentGatewayQueryTest {

    private final StubPaymentGateway gw = new StubPaymentGateway(new PayProperties());

    @Test
    @DisplayName("queryCharge → notPaid(EXPIRED)，回调带同一 gateway_ref")
    void notPaidExpired() {
        ChargeQueryResult r = gw.queryCharge("stub-abc");
        assertThat(r.outcome()).isEqualTo(ChargeQueryResult.Outcome.NOT_PAID);
        assertThat(r.callback().status()).isEqualTo(GatewayStatus.EXPIRED);
        assertThat(r.callback().gatewayRef()).isEqualTo("stub-abc");
        assertThat(r.isTerminalNotPaid()).isTrue();
    }

    @Test
    @DisplayName("空 ref → unknown；接口默认实现（如 Midtrans）→ unknown")
    void blankAndDefault() {
        assertThat(gw.queryCharge(" ").outcome()).isEqualTo(ChargeQueryResult.Outcome.UNKNOWN);
        PaymentGateway bare = new MidtransGateway(new PayProperties());
        assertThat(bare.queryCharge("x").outcome()).isEqualTo(ChargeQueryResult.Outcome.UNKNOWN);
        assertThat(bare.queryCharge("x").callback()).isNull();
    }
}
