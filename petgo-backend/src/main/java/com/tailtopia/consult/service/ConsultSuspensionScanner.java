package com.tailtopia.consult.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 封禁挂起 15min 超时扫描（Story 3.8，H-5，§调度⑥）。Spring 原生 {@code @Scheduled}（照 {@code ConsultCloseScanner}/
 * {@code ConsultRequestTimeoutScanner}）→ 强制结束+退款，<b>禁 MQ/延迟队列</b>（架构护栏）。异常不 crash 调度。
 */
@Component
public class ConsultSuspensionScanner {

    private static final Logger log = LoggerFactory.getLogger(ConsultSuspensionScanner.class);

    private final ConsultSuspensionService service;

    public ConsultSuspensionScanner(ConsultSuspensionService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${petgo.consult.suspend-scan-ms:30000}")
    public void scan() {
        try {
            int handled = service.scanExpiredSuspensions();
            if (handled > 0) {
                log.info("封禁挂起超时强制结束+退款 handled={}", handled);
            }
        } catch (RuntimeException e) {
            log.warn("封禁挂起超时扫描失败 cause={}", e.getClass().getSimpleName());
        }
    }
}
