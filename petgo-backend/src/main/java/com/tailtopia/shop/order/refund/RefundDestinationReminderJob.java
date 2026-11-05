package com.tailtopia.shop.order.refund;

import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 现金段 7 天未选去向的运营提醒（v1.4.2 shop Story 4.1 AC6.2，架构 D-15）：每天 WIB 09:00 扫描
 * {@code AWAITING_DESTINATION} 且建单早于 now − 7 天、尚未提醒过的退款单 → WARN 日志 + 写 {@code destination_reminded_at}
 * （每张只提醒一次）。后台线经 {@code ShopOrderRefundService#findAwaitingDestinationOverdue} 展示待办。
 *
 * <p>🔴 <b>不推送用户</b>（PRD 11-05）。🔒 日志只记退款单 token 与订单展示号。
 */
@Component
public class RefundDestinationReminderJob {

    private static final Logger log = LoggerFactory.getLogger(RefundDestinationReminderJob.class);

    static final Duration THRESHOLD = Duration.ofDays(7);

    private final ShopOrderRefundRepository refunds;
    private final ShopOrderRepository orders;
    private final Clock clock;

    @Autowired
    public RefundDestinationReminderJob(ShopOrderRefundRepository refunds, ShopOrderRepository orders) {
        this(refunds, orders, Clock.systemUTC());
    }

    RefundDestinationReminderJob(ShopOrderRefundRepository refunds, ShopOrderRepository orders, Clock clock) {
        this.refunds = refunds;
        this.orders = orders;
        this.clock = clock;
    }

    /** @return 本次提醒的退款单数 */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Jakarta")
    @Transactional
    public int run() {
        Instant now = clock.instant();
        List<ShopOrderRefund> due = refunds
                .findByStatusAndCreatedAtBeforeAndDestinationRemindedAtIsNullOrderByCreatedAtAsc(
                        ShopRefundStatus.AWAITING_DESTINATION, now.minus(THRESHOLD));
        for (ShopOrderRefund r : due) {
            String displayNo = orders.findById(r.getOrderId()).map(ShopOrder::getDisplayNo).orElse("?");
            log.warn("取消退款单超 7 天未选择退款方式 refund={} order={}", r.getPublicToken(), displayNo);
            r.markDestinationReminded(now);
            refunds.save(r);
        }
        return due.size();
    }
}
