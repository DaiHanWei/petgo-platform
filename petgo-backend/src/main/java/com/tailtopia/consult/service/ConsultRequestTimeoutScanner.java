package com.tailtopia.consult.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 入队超时静默删除定时扫描（Story 3.2）。DB 状态机驱动（{@code @Scheduled}，<b>禁 MQ / 延迟队列</b>，enforcement 护栏）：
 * 周期扫 {@code consult_requests} 中 QUEUEING 且 {@code queue_deadline_at} 已过期的行 → 物理删（无痕、不建订单，A-5）。
 *
 * <p>固定延迟 30s 扫一次（1min 入队窗对 30s 精度足够）。已接单（ACCEPTED_AWAIT_PAY）不被本扫描删（其支付窗超时属 3-4）。
 */
@Component
public class ConsultRequestTimeoutScanner {

    private static final Logger log = LoggerFactory.getLogger(ConsultRequestTimeoutScanner.class);

    private final ConsultRequestService requestService;

    public ConsultRequestTimeoutScanner(ConsultRequestService requestService) {
        this.requestService = requestService;
    }

    @Scheduled(fixedDelayString = "${petgo.consult.queue-timeout-scan-ms:30000}")
    public void scan() {
        try {
            int purged = requestService.purgeExpiredQueue();
            if (purged > 0) {
                log.info("入队超时静默删除 count={}", purged);
            }
        } catch (RuntimeException e) {
            log.warn("入队超时扫描失败 cause={}", e.getClass().getSimpleName());
        }
    }
}
