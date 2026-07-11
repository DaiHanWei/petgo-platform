package com.tailtopia.shared.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * L0：支付装配 fail-closed 护栏（Review P1）。资金入口配置错误绝不能静默上桩/漏凭证。
 */
class PayConfigTest {

    private final PayConfig config = new PayConfig();

    private PayProperties props(String mode, String serverKey, String callbackToken) {
        PayProperties p = new PayProperties();
        p.setMode(mode);
        p.setServerKey(serverKey);
        p.setCallbackToken(callbackToken);
        return p;
    }

    private MockEnvironment env(String... profiles) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profiles);
        return e;
    }

    @Test
    void devStubAssemblesStubGateway() {
        PaymentGateway gw = config.paymentGateway(props("stub", "", ""), env("dev"));
        assertThat(gw).isInstanceOf(StubPaymentGateway.class);
    }

    @Test
    void liveWithCredsAssemblesMidtransGateway() {
        PaymentGateway gw = config.paymentGateway(props("live", "SB-Mid-server-XYZ", "cbtoken"), env("prod"));
        assertThat(gw).isInstanceOf(MidtransGateway.class);
    }

    @Test
    void prodWithStubIsRejected() {
        // prod 禁止上桩网关（桩验签宽松 → 回调门大开）。
        assertThatThrownBy(() -> config.paymentGateway(props("stub", "", ""), env("prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void liveWithoutServerKeyIsRejected() {
        assertThatThrownBy(() -> config.paymentGateway(props("live", "", "cbtoken"), env("prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void liveWithoutCallbackTokenIsRejected() {
        assertThatThrownBy(() -> config.paymentGateway(props("live", "SB-Mid-server-XYZ", ""), env("prod")))
                .isInstanceOf(IllegalStateException.class);
    }
}
