package com.tailtopia.shop.logistics;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 在途运单回查（v1.4.2 shop Story 5.1 AC7.5）：每 6 小时对 Biteship 活动、未签收 / 未取消的运单逐条回查并推进
 * （单次 ≤ 500 条，单条失败只 WARN）。兜底 Biteship 不重发 Webhook。
 *
 * <p>防重入：Redis 锁（见 {@link BiteshipJobLock}）。
 */
@Component
public class BiteshipActivePollJob {

    private static final Logger log = LoggerFactory.getLogger(BiteshipActivePollJob.class);

    static final String LOCK_KEY = "lock:shop-biteship-active-poll";
    static final Duration LOCK_TTL = Duration.ofMinutes(60);
    static final int BATCH = 500;

    private final BiteshipTrackingService tracking;
    private final StringRedisTemplate redis;

    public BiteshipActivePollJob(BiteshipTrackingService tracking, StringRedisTemplate redis) {
        this.tracking = tracking;
        this.redis = redis;
    }

    @Scheduled(cron = "${petgo.shop.biteship.poll-cron:0 0 */6 * * *}", zone = "UTC")
    public void scheduled() {
        try {
            int n = run();
            log.info("Biteship 在途回查一轮 changed={}", n);
        } catch (RuntimeException e) {
            log.warn("Biteship 在途回查整轮失败 cause={}", e.getClass().getSimpleName());
        }
    }

    public int run() {
        return BiteshipJobLock.runLocked(redis, LOCK_KEY, LOCK_TTL, () -> tracking.pollInFlight(BATCH));
    }
}
