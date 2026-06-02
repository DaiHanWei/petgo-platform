package com.petgo.consult.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 评分门 30min 超时定时扫描（Story 5.6）。DB 状态机驱动（{@code @Scheduled}，<b>禁 MQ</b>）：
 * 周期扫 PENDING_CLOSE 超 30min 未评分的会话 → 自动 CLOSED(UNRATED) + 置补弹 PENDING。
 *
 * <p>固定延迟 60s 扫一次（30min 窗口对 1min 精度足够，避免高频扫库）。
 */
@Component
public class ConsultCloseScanner {

    private static final Logger log = LoggerFactory.getLogger(ConsultCloseScanner.class);

    private final ConsultCloseService closeService;

    public ConsultCloseScanner(ConsultCloseService closeService) {
        this.closeService = closeService;
    }

    @Scheduled(fixedDelayString = "${petgo.consult.rating-gate-scan-ms:60000}")
    public void scan() {
        try {
            int closed = closeService.closeExpiredGates();
            if (closed > 0) {
                log.info("评分门超时自动关闭会话 count={}", closed);
            }
        } catch (RuntimeException e) {
            log.warn("评分门超时扫描失败 cause={}", e.getClass().getSimpleName());
        }
    }
}
