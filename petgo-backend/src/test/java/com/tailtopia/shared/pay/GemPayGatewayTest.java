package com.tailtopia.shared.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * L0：GemPay 验签（UNCONFIRMED 公式的机制）+ 回调归一化/脱敏 + 渠道映射 + 状态映射。
 * 无网络（{@code createCharge} 走 HTTP 属 L2，不在此覆盖，同 {@code MidtransGatewayTest}）。
 */
class GemPayGatewayTest {

    private PayProperties props(String merchantId, String secret) {
        PayProperties p = new PayProperties();
        p.setMode("live");
        p.setProvider("gempay");
        PayProperties.Gempay g = p.getGempay();
        g.setMerchantId(merchantId);
        g.setProjectNo("PROJECT001");
        g.setMerchantSecret(secret);
        return p;
    }

    private GemPayGateway gateway(String merchantId, String secret) {
        return new GemPayGateway(props(merchantId, secret));
    }

    // ===== 验签 =====

    /** 构造一个签名正确的回调正文（用与 gateway 同配置：merchantId + projectNo=PROJECT001）。 */
    private Map<String, Object> signedCallback(String merchantId, String secret,
            String requestId, String amount, String channel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("amount", amount);
        body.put("channel", channel);
        body.put("status", "success");
        body.put("ref_id", "GP-REF-1");
        body.put("signature",
                GemPaySignature.callback(requestId, amount, merchantId, channel, secret, "PROJECT001"));
        return body;
    }

    @Test
    void verifyCallbackAcceptsCorrectSignature() {
        String merchantId = "KMB0000";
        String secret = "f3c53530fc444b3afa63d2c406dd7438";
        Map<String, Object> body = signedCallback(merchantId, secret, "R19K251220", "10000", "MBayar_QR");
        assertThat(gateway(merchantId, secret).verifyCallback(body)).isTrue();
    }

    @Test
    void verifyCallbackAcceptsUppercaseSignature() {
        String merchantId = "KMB0000";
        String secret = "sekret";
        Map<String, Object> body = signedCallback(merchantId, secret, "R-2", "20000", "MBayar_QR");
        body.put("signature", body.get("signature").toString().toUpperCase()); // 大小写不敏感
        assertThat(gateway(merchantId, secret).verifyCallback(body)).isTrue();
    }

    @Test
    void verifyCallbackRejectsTamperedSignature() {
        Map<String, Object> body = signedCallback("KMB0000", "sekret", "R-3", "10000", "MBayar_QR");
        body.put("signature", "deadbeefdeadbeefdeadbeefdeadbeef"); // 篡改
        assertThat(gateway("KMB0000", "sekret").verifyCallback(body)).isFalse();
    }

    @Test
    void verifyCallbackRejectsMissingFields() {
        Map<String, Object> noSig = new LinkedHashMap<>();
        noSig.put("request_id", "R-4"); // 缺 amount/channel/signature
        assertThat(gateway("KMB0000", "sekret").verifyCallback(noSig)).isFalse();
        assertThat(gateway("KMB0000", "sekret").verifyCallback(null)).isFalse();
    }

    @Test
    void verifyCallbackFailsClosedWhenSecretMissing() {
        // secret 未配置 → 一律拒（fail-closed），即便签名字段齐全。
        Map<String, Object> body = signedCallback("KMB0000", "sekret", "R-5", "10000", "MBayar_QR");
        assertThat(gateway("KMB0000", "").verifyCallback(body)).isFalse();
    }

    // ===== 回调归一化 + 脱敏 =====

    @Test
    void parseCallbackMapsGemPayFieldsAndStripsSecrets() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", "TOKEN-abc"); // → orderId (=public_token)
        body.put("ref_id", "GP-REF-9"); // → gatewayRef
        body.put("status", "success"); // → PAID
        body.put("amount", "20000");
        body.put("signature", "sig-should-be-stripped");
        body.put("merchant_secret", "secret-should-be-stripped");

        PaymentCallback cb = gateway("KMB0000", "sekret").parseCallback(body);

        assertThat(cb.orderId()).isEqualTo("TOKEN-abc");
        assertThat(cb.gatewayRef()).isEqualTo("GP-REF-9");
        assertThat(cb.status()).isEqualTo(GatewayStatus.PAID);
        // 脱敏铁律：签名/密钥绝不进 rawMeta。
        assertThat(cb.rawMeta()).doesNotContainKeys("signature", "merchant_secret");
        assertThat(cb.rawMeta()).containsEntry("amount", "20000");
    }

    @Test
    void parseCallbackFailureMapsToFailed() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", "T-1");
        body.put("ref_id", "R-1");
        body.put("status", "failure");
        assertThat(gateway("M", "s").parseCallback(body).status()).isEqualTo(GatewayStatus.FAILED);
    }

    // ===== 状态映射 =====

    @Test
    void gatewayStatusFromGemPay() {
        assertThat(GatewayStatus.fromGemPay("success")).isEqualTo(GatewayStatus.PAID);
        assertThat(GatewayStatus.fromGemPay("SUCCESS")).isEqualTo(GatewayStatus.PAID);
        assertThat(GatewayStatus.fromGemPay("failure")).isEqualTo(GatewayStatus.FAILED);
        assertThat(GatewayStatus.fromGemPay("expired")).isEqualTo(GatewayStatus.EXPIRED);
        assertThat(GatewayStatus.fromGemPay("pending")).isEqualTo(GatewayStatus.PENDING);
        assertThat(GatewayStatus.fromGemPay(null)).isEqualTo(GatewayStatus.PENDING);
        assertThat(GatewayStatus.fromGemPay("weird")).isEqualTo(GatewayStatus.PENDING);
    }

    // ===== /history 查询状态归一化（含复合态）=====

    @Test
    void historyStatusNormalization() {
        assertThat(GemPayGateway.normalizeHistoryStatus("Success")).isEqualTo(GatewayStatus.PAID);
        // 复合态：最终已付 → PAID。
        assertThat(GemPayGateway.normalizeHistoryStatus("Failure => Success")).isEqualTo(GatewayStatus.PAID);
        assertThat(GemPayGateway.normalizeHistoryStatus("Expired => Success")).isEqualTo(GatewayStatus.PAID);
        assertThat(GemPayGateway.normalizeHistoryStatus("Failure")).isEqualTo(GatewayStatus.FAILED);
        assertThat(GemPayGateway.normalizeHistoryStatus("Expired")).isEqualTo(GatewayStatus.EXPIRED);
        assertThat(GemPayGateway.normalizeHistoryStatus("Pending")).isEqualTo(GatewayStatus.PENDING);
        assertThat(GemPayGateway.normalizeHistoryStatus("Initial")).isEqualTo(GatewayStatus.PENDING);
        assertThat(GemPayGateway.normalizeHistoryStatus(null)).isEqualTo(GatewayStatus.PENDING);
    }

    // ===== 渠道映射 =====

    @Test
    void channelMapsQrisToMBayarQr() {
        assertThat(GemPayGateway.toGemPayChannel("QRIS")).isEqualTo("MBayar_QR");
        assertThat(GemPayGateway.toGemPayChannel("qris")).isEqualTo("MBayar_QR");
    }

    @Test
    void channelRejectsUnsupportedAndNull() {
        assertThatThrownBy(() -> GemPayGateway.toGemPayChannel("PAWCOIN"))
                .isInstanceOf(PayException.class);
        assertThatThrownBy(() -> GemPayGateway.toGemPayChannel(null))
                .isInstanceOf(PayException.class);
    }
}
