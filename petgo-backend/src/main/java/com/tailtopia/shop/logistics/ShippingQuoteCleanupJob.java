package com.tailtopia.shop.logistics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 报价清理（v1.4.2 shop Story 1.1，D-9）：每日删除 2 天前的报价，单次最多 5,000 行。
 *
 * <p>🔴 「被订单引用的不删」由 Story 3.2 补：3.2 加 {@code shop_orders.shipping_quote_id} 后，在
 * {@link ShippingQuoteRecordRepository#deleteCreatedBefore} 的子查询追加
 * {@code AND NOT EXISTS (SELECT 1 FROM shop_orders o WHERE o.shipping_quote_id = q.id)}。
 */
@Component
public class ShippingQuoteCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ShippingQuoteCleanupJob.class);

    static final Duration RETENTION = Duration.ofDays(2);
    static final int BATCH_LIMIT = 5_000;

    private final ShippingQuoteRecordRepository quotes;
    private final Clock clock;

    @Autowired
    public ShippingQuoteCleanupJob(ShippingQuoteRecordRepository quotes) {
        this(quotes, Clock.systemUTC());
    }

    ShippingQuoteCleanupJob(ShippingQuoteRecordRepository quotes, Clock clock) {
        this.quotes = quotes;
        this.clock = clock;
    }

    @Scheduled(cron = "${petgo.shop.shipping-quote-cleanup-cron:0 30 3 * * *}", zone = "Asia/Jakarta")
    public int run() {
        Instant threshold = clock.instant().minus(RETENTION);
        int deleted = quotes.deleteCreatedBefore(threshold, BATCH_LIMIT);
        log.info("shipping quote cleanup deleted={}", deleted);
        return deleted;
    }
}
