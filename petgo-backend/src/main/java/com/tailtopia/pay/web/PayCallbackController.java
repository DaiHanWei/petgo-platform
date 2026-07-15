package com.tailtopia.pay.web;

import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shared.pay.PaymentGateway;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付网关回调（Story 1.1 · GemPay 对接）。{@code POST /pay/callback}（<b>不带 {@code /api/v1}</b>，内网/白名单
 * 语义同 {@code /im/callback}）：收款状态推送入口。
 *
 * <p>GemPay 回调为 {@code application/x-www-form-urlencoded}（<b>非 JSON</b>）——故绑 form 参数并归一成 Map 契约；
 * 先验签（{@link PaymentGateway#verifyCallback} 失败 → 403 {@link AppException#forbidden}），再归一化并幂等推进意图
 * （回调/轮询双通道单一收口去重）。按 GemPay 约定回 {@code {"status":true,"error_code":"00","error_desc":""}}。
 * 网关无关：Controller 只把 form 当 Map 传给当前网关（stub / gempay 各读自家字段）。
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

    @PostMapping(path = "/pay/callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String, Object> callback(@RequestParam MultiValueMap<String, String> form) {
        Map<String, Object> body = new LinkedHashMap<>(form.toSingleValueMap());
        if (!gateway.verifyCallback(body)) {
            throw AppException.forbidden("非法回调");
        }
        PaymentCallback cb = gateway.parseCallback(body);
        paymentIntentService.applyCallback(cb);
        return Map.of("status", true, "error_code", "00", "error_desc", "");
    }
}
