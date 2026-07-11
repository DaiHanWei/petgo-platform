package com.tailtopia.pay.web;

import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shared.pay.PaymentGateway;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付网关回调（Story 1.1）。{@code POST /pay/callback}（<b>不带 {@code /api/v1}</b>，内网/白名单语义同
 * {@code /im/callback}）：Midtrans 收款状态推送入口。
 *
 * <p>先验签（{@link PaymentGateway#verifyCallback} 失败 → 403 {@link AppException#forbidden}），再归一化并
 * 幂等推进意图（回调/轮询双通道单一收口去重）。按 Midtrans 期望回 200 OK 应答。
 * <b>绝不打印 body / 签名 / 凭证</b>。
 */
@RestController
public class PayCallbackController {

    private final PaymentGateway gateway;
    private final PaymentIntentService paymentIntentService;

    public PayCallbackController(PaymentGateway gateway, PaymentIntentService paymentIntentService) {
        this.gateway = gateway;
        this.paymentIntentService = paymentIntentService;
    }

    @PostMapping("/pay/callback")
    public Map<String, Object> callback(@RequestBody(required = false) Map<String, Object> body) {
        if (!gateway.verifyCallback(body)) {
            throw AppException.forbidden("非法回调");
        }
        PaymentCallback cb = gateway.parseCallback(body);
        paymentIntentService.applyCallback(cb);
        return Map.of("status", "OK");
    }
}
