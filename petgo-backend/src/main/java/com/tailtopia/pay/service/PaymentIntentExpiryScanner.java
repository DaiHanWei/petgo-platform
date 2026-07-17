package com.tailtopia.pay.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 支付意图付款窗过期扫描（V85）。DB 状态机驱动（{@code @Scheduled}，<b>禁 MQ / 延迟队列</b>，enforcement 护栏）：
 * PENDING 且 {@code expires_at < now} 的意图（当前仅充值 60min 窗）→ 置 EXPIRED。
 *
 * <p>用户可见的过期正确性由懒过期承担（{@code statusOf} 轮询即见过期 / {@code findReusablePending} 复用时跳过），
 * 本扫描是<b>兜底清理</b>——无人轮询的过窗充值不至长期滞留 PENDING。固定延迟 1min 扫一次（60min 窗对 1min 精度足够）。
 * try/catch 隔离，单次失败不 crash 调度。
 */
@Component
public class PaymentIntentExpiryScanner {

    private static final Logger log = LoggerFactory.getLogger(PaymentIntentExpiryScanner.class);

    private static final int BATCH_LIMIT = 200;

    private final PaymentIntentService paymentIntentService;

    public PaymentIntentExpiryScanner(PaymentIntentService paymentIntentService) {
        this.paymentIntentService = paymentIntentService;
    }

    @Scheduled(fixedDelayString = "${petgo.pay.intent-expiry-scan-ms:60000}")
    public void scan() {
        try {
            int expired = paymentIntentService.expireOverduePending(BATCH_LIMIT);
            if (expired > 0) {
                log.info("支付意图付款窗过期置 EXPIRED count={}", expired);
            }
        } catch (RuntimeException e) {
            log.warn("支付意图过期扫描失败 cause={}", e.getClass().getSimpleName());
        }
    }
}
