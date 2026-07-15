package com.tailtopia.shared.pay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * L0：Midtrans 验签确定性 + 回调归一化/脱敏（无凭证、无网络，纯 JDK）。
 */
class MidtransGatewayTest {

    private PayProperties props(String serverKey) {
        PayProperties p = new PayProperties();
        p.setMode("live");
        p.setServerKey(serverKey);
        return p;
    }

    private MidtransGateway gateway(String serverKey) {
        return new MidtransGateway(props(serverKey));
    }

    @Test
    void sha512HexIsDeterministic() {
        String a = MidtransGateway.sha512Hex("order-1200SK");
        String b = MidtransGateway.sha512Hex("order-1200SK");
        assertThat(a).isEqualTo(b).hasSize(128).matches("[0-9a-f]+");
    }

    @Test
    void verifyCallbackAcceptsCorrectSignature() {
        String serverKey = "SB-Mid-server-XYZ";
        // Midtrans 约定：SHA-512(order_id + status_code + gross_amount + serverKey)
        String sig = MidtransGateway.sha512Hex("ORDER-9" + "200" + "10000.00" + serverKey);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_id", "ORDER-9");
        body.put("status_code", "200");
        body.put("gross_amount", "10000.00");
        body.put("signature_key", sig);

        assertThat(gateway(serverKey).verifyCallback(body)).isTrue();
    }

    @Test
    void verifyCallbackRejectsTamperedSignatureAndMissingFields() {
        String serverKey = "SB-Mid-server-XYZ";
        Map<String, Object> tampered = new LinkedHashMap<>();
        tampered.put("order_id", "ORDER-9");
        tampered.put("status_code", "200");
        tampered.put("gross_amount", "10000.00");
        tampered.put("signature_key", "deadbeef"); // 错误签名
        assertThat(gateway(serverKey).verifyCallback(tampered)).isFalse();

        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("order_id", "ORDER-9");
        assertThat(gateway(serverKey).verifyCallback(missing)).isFalse();
        assertThat(gateway(serverKey).verifyCallback(null)).isFalse();
    }

    @Test
    void parseCallbackMapsStatusAndStripsSignatureFromMeta() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_id", "ORDER-9");
        body.put("transaction_id", "midtrans-tx-1");
        body.put("transaction_status", "settlement");
        body.put("signature_key", "should-not-leak");

        PaymentCallback cb = gateway("k").parseCallback(body);

        assertThat(cb.orderId()).isEqualTo("ORDER-9");
        assertThat(cb.gatewayRef()).isEqualTo("midtrans-tx-1");
        assertThat(cb.status()).isEqualTo(GatewayStatus.PAID);
        // 脱敏铁律：signature_key 绝不进 rawMeta
        assertThat(cb.rawMeta()).doesNotContainKey("signature_key");
        assertThat(cb.rawMeta()).containsEntry("order_id", "ORDER-9");
    }

    @Test
    void gatewayStatusMappingCoversTerminalAndPending() {
        assertThat(GatewayStatus.fromMidtrans("settlement")).isEqualTo(GatewayStatus.PAID);
        assertThat(GatewayStatus.fromMidtrans("capture")).isEqualTo(GatewayStatus.PAID);
        assertThat(GatewayStatus.fromMidtrans("deny")).isEqualTo(GatewayStatus.FAILED);
        assertThat(GatewayStatus.fromMidtrans("cancel")).isEqualTo(GatewayStatus.FAILED);
        assertThat(GatewayStatus.fromMidtrans("expire")).isEqualTo(GatewayStatus.EXPIRED);
        assertThat(GatewayStatus.fromMidtrans("pending")).isEqualTo(GatewayStatus.PENDING);
        assertThat(GatewayStatus.fromMidtrans(null)).isEqualTo(GatewayStatus.PENDING);
    }

    @Test
    void stubGatewayChargeIsDeterministicAndVerifiesToken() {
        PayProperties p = new PayProperties(); // mode=stub 默认
        p.setCallbackToken("tok-123");
        StubPaymentGateway stub = new StubPaymentGateway(p);

        ChargeResult r = stub.createCharge(new ChargeRequest("ORDER-1", 10000L, "IDR", "QRIS", "PAWCOIN_TOPUP"));
        assertThat(r.gatewayRef()).isEqualTo("stub-ORDER-1");
        assertThat(r.payload()).isEqualTo("stub-qr://ORDER-1");

        assertThat(stub.verifyCallback(Map.of("signature_key", "tok-123"))).isTrue();
        assertThat(stub.verifyCallback(Map.of("signature_key", "wrong"))).isFalse();
    }
}
