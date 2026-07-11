package com.tailtopia.shared.pay;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 打桩支付网关（Story 1.1，{@code petgo.pay.mode=stub}，默认装配）。无凭证确定性实现，使意图状态机 /
 * 回调双通道去重在 L0/L1 可验（真实 Midtrans 收款属 L2）。
 *
 * <p>桩沿用 Midtrans 回调字段名（{@code order_id} / {@code transaction_id} / {@code transaction_status} /
 * {@code signature_key}），故与 {@link MidtransGateway} 共用一套 {@link #parseCallback} 归一化 +
 * {@link GatewayStatus#fromMidtrans} 映射。验签只比固定 {@code callbackToken}（未配置则放行，仅 dev/L0）。
 */
public class StubPaymentGateway implements PaymentGateway {

    private final PayProperties props;

    public StubPaymentGateway(PayProperties props) {
        this.props = props;
    }

    @Override
    public ChargeResult createCharge(ChargeRequest request) {
        // 确定性伪单号（由 orderId 派生），付款载荷用伪二维码串；rawMeta 明示为桩，绝不含凭证。
        String gatewayRef = "stub-" + request.orderId();
        String payload = "stub-qr://" + request.orderId();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("stub", true);
        meta.put("channel", request.channel());
        return new ChargeResult(gatewayRef, payload, meta);
    }

    @Override
    public boolean verifyCallback(Map<String, Object> body) {
        String expected = props.getCallbackToken();
        if (expected == null || expected.isBlank()) {
            return true; // 未配置 token：dev/L0 放行（内网/白名单兜底）
        }
        Object sig = body == null ? null : body.get("signature_key");
        return sig != null && expected.equals(sig.toString());
    }

    @Override
    public PaymentCallback parseCallback(Map<String, Object> body) {
        return CallbackParser.parse(body);
    }
}
