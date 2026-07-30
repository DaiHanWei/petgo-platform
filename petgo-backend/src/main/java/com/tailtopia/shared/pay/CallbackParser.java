package com.tailtopia.shared.pay;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 回调正文归一化（Story 1.1）。桩/实共用：读 Midtrans 约定字段并脱敏落 rawMeta。
 *
 * <p>脱敏铁律：{@code signature_key} 等敏感字段<b>绝不进 rawMeta</b>（避免签名随快照落库/漂日志）。
 */
final class CallbackParser {

    private CallbackParser() {
    }

    static PaymentCallback parse(Map<String, Object> body) {
        String orderId = str(body, "order_id");
        String gatewayRef = str(body, "transaction_id");
        GatewayStatus status = GatewayStatus.fromMidtrans(str(body, "transaction_status"));
        return new PaymentCallback(orderId, gatewayRef, status, sanitize(body));
    }

    /** 拷贝快照并剔除敏感字段（签名绝不落库）。 */
    private static Map<String, Object> sanitize(Map<String, Object> body) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (body != null) {
            body.forEach((k, v) -> {
                if (!"signature_key".equalsIgnoreCase(k)) {
                    meta.put(k, v);
                }
            });
        }
        return meta;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : v.toString();
    }
}
