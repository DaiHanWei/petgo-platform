package com.tailtopia.shop.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 电商订单支付窗超时扫描（Story 3.8，AD-8）。
 *
 * <p>DB 状态机驱动（{@code @Scheduled}，🔴 <b>禁 MQ / 延迟队列</b>，enforcement 护栏）：
 * {@code PENDING_PAYMENT} 且 {@code expires_at < now} → 取消并<b>释放库存</b>。
 *
 * <p>用户可见的正确性由<b>懒过期</b>承担（读详情 / 点支付时就地判定）；本扫描是兜底 ——
 * 无人查看的订单不该永远锁着库存。🔴 <b>这一条比意图过期扫描重要得多</b>：
 * 支付意图滞留 PENDING 只是脏数据，而订单滞留 PENDING_PAYMENT 会一直占着别人想买的货。
 *
 * <p>固定延迟 1min（60min 窗对 1min 精度足够）。try/catch 隔离，单次失败不 crash 调度。
 */
@Component
public class ShopOrderExpiryScanner {

    private static final Logger log = LoggerFactory.getLogger(ShopOrderExpiryScanner.class);

    private static final int BATCH_LIMIT = 200;

    private final ShopOrderPaymentService payments;

    public ShopOrderExpiryScanner(ShopOrderPaymentService payments) {
        this.payments = payments;
    }

    @Scheduled(fixedDelayString = "${petgo.shop.order-expiry-scan-ms:60000}")
    public void scan() {
        try {
            int cancelled = payments.cancelOverdue(BATCH_LIMIT);
            if (cancelled > 0) {
                log.info("电商订单支付超时取消并释放库存 count={}", cancelled);
            }
        } catch (RuntimeException e) {
            log.warn("电商订单超时扫描失败 cause={}", e.getClass().getSimpleName());
        }
    }
}
