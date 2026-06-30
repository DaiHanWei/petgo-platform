package com.tailtopia.admin.moderation.service;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 人工审核超时每日扫描（Story 4.3，AC6）。{@code @Scheduled} + DB 状态机幂等去重——
 * 将超 3 天仍 PENDING 的项置 TIMED_OUT + 丢弃内容 + 通知作者；开关关闭时 {@link ManualReviewService#scanTimeouts}
 * 早返回（空转）。<b>禁 MQ/调度/缓存中间件（F5）</b>，沿用 {@code SipdhExpiryScanner} 范式。
 */
@Component
public class ReviewTimeoutScanner {

    private static final Logger log = LoggerFactory.getLogger(ReviewTimeoutScanner.class);

    private final ManualReviewService reviewService;

    public ReviewTimeoutScanner(ManualReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** 每日扫描（默认每天 04:00 UTC，cron 可经 env 覆盖）。 */
    @Scheduled(cron = "${petgo.moderation.review-timeout-scan-cron:0 0 4 * * *}", zone = "UTC")
    public void scan() {
        try {
            reviewService.scanTimeouts(Instant.now());
        } catch (RuntimeException e) {
            log.warn("人工审核超时扫描失败 cause={}", e.getClass().getSimpleName());
        }
    }
}
