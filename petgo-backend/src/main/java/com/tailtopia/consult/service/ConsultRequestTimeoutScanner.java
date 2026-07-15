package com.tailtopia.consult.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 计费流咨询请求超时扫描（Story 3.2/3.3）。DB 状态机驱动（{@code @Scheduled}，<b>禁 MQ / 延迟队列</b>，enforcement 护栏），
 * 两条独立扫描：
 * <ul>
 *   <li>{@link #scan()}（3.2）：QUEUEING 且 {@code queue_deadline_at} 过期 → 物理删（无痕、不建订单，A-5）。</li>
 *   <li>{@link #scanPayWindow()}（3.3）：ACCEPTED_AWAIT_PAY 且 {@code pay_deadline_at} 过期（用户未支付）→
 *       作废接单回 QUEUEING 重播（释放兽医 + rebroadcast_count++ + 再次广播在线兽医）。</li>
 * </ul>
 *
 * <p>各固定延迟 30s 扫一次（1min 入队窗 / 1.5min 支付窗对 30s 精度足够）。两扫描 state 谓词互不干扰：队列扫描
 * 不动已接单行，支付窗扫描只动 ACCEPTED_AWAIT_PAY 行。try/catch 隔离，单次失败不 crash 调度。
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

    /** 支付窗超时作废接单回队重播（Story 3.3）。用户未在 1.5min 内支付 → 释放兽医、请求回 QUEUEING 再广播。 */
    @Scheduled(fixedDelayString = "${petgo.consult.pay-window-scan-ms:30000}")
    public void scanPayWindow() {
        try {
            int reverted = requestService.revertExpiredAcceptances();
            if (reverted > 0) {
                log.info("支付窗超时作废接单回队重播 count={}", reverted);
            }
        } catch (RuntimeException e) {
            log.warn("支付窗超时扫描失败 cause={}", e.getClass().getSimpleName());
        }
    }
}
