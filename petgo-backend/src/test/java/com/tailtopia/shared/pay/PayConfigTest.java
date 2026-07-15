package com.tailtopia.shared.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * L0：支付装配 fail-closed 护栏（Review P1 · GemPay 对接）。资金入口配置错误绝不能静默上桩/漏凭证。
 * 覆盖 provider 双分支：{@code gempay}（默认，印尼落地）与 {@code midtrans}（保留旧路径）。
 */
class PayConfigTest {

    private final PayConfig config = new PayConfig();

    /** Midtrans 旧路径配置（显式 provider=midtrans）。 */
    private PayProperties midtransProps(String mode, String serverKey, String callbackToken) {
        PayProperties p = new PayProperties();
        p.setMode(mode);
        p.setProvider("midtrans");
        p.setServerKey(serverKey);
        p.setCallbackToken(callbackToken);
        return p;
    }

    /** GemPay 配置（默认 provider=gempay）。 */
    private PayProperties gempayProps(String mode, String merchantId, String projectNo, String secret) {
        PayProperties p = new PayProperties();
        p.setMode(mode);
        // provider 默认即 gempay
        PayProperties.Gempay g = p.getGempay();
        g.setMerchantId(merchantId);
        g.setProjectNo(projectNo);
        g.setMerchantSecret(secret);
        return p;
    }

    private MockEnvironment env(String... profiles) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profiles);
        return e;
    }

    @Test
    void devStubAssemblesStubGateway() {
        PaymentGateway gw = config.paymentGateway(gempayProps("stub", "", "", ""), env("dev"));
        assertThat(gw).isInstanceOf(StubPaymentGateway.class);
    }

    @Test
    void prodWithStubIsRejected() {
        // prod 禁止上桩网关（桩验签宽松 → 回调门大开）。
        assertThatThrownBy(() -> config.paymentGateway(gempayProps("stub", "", "", ""), env("prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    // ===== GemPay 分支（默认 provider）=====

    @Test
    void liveGemPayWithCredsAssemblesGemPayGateway() {
        PaymentGateway gw = config.paymentGateway(
                gempayProps("live", "KMB0000", "PROJECT001", "sekret"), env("prod"));
        assertThat(gw).isInstanceOf(GemPayGateway.class);
    }

    @Test
    void liveGemPayMissingMerchantIdIsRejected() {
        assertThatThrownBy(() -> config.paymentGateway(
                gempayProps("live", "", "PROJECT001", "sekret"), env("prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void liveGemPayMissingProjectNoIsRejected() {
        assertThatThrownBy(() -> config.paymentGateway(
                gempayProps("live", "KMB0000", "", "sekret"), env("prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void liveGemPayMissingSecretIsRejected() {
        assertThatThrownBy(() -> config.paymentGateway(
                gempayProps("live", "KMB0000", "PROJECT001", ""), env("prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    // ===== Midtrans 保留路径 =====

    @Test
    void liveMidtransWithCredsAssemblesMidtransGateway() {
        PaymentGateway gw = config.paymentGateway(
                midtransProps("live", "SB-Mid-server-XYZ", "cbtoken"), env("prod"));
        assertThat(gw).isInstanceOf(MidtransGateway.class);
    }

    @Test
    void liveMidtransWithoutServerKeyIsRejected() {
        assertThatThrownBy(() -> config.paymentGateway(
                midtransProps("live", "", "cbtoken"), env("prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void liveMidtransWithoutCallbackTokenIsRejected() {
        assertThatThrownBy(() -> config.paymentGateway(
                midtransProps("live", "SB-Mid-server-XYZ", ""), env("prod")))
                .isInstanceOf(IllegalStateException.class);
    }
}
