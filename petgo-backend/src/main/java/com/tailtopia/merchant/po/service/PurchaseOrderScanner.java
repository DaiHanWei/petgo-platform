package com.tailtopia.merchant.po.service;

import com.tailtopia.merchant.po.repository.PurchaseOrderIdempotencyStore;
import com.tailtopia.merchant.po.repository.PurchaseOrderRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 进货单的时间驱动兜底扫描（DB 状态机 + {@code @Scheduled}，禁 MQ）。各段独立 try/catch。
 *
 * <ol>
 *   <li>付款超时（1min）：读接口已惰性取消，这里兜底无人查看的单 —— 锁着的库存是 Toko 用户也要买的货。</li>
 *   <li>自动送达 / 自动完成（10min）：7 日级时限。</li>
 *   <li>幂等记录清理（每日）：保留 ≥ 7 天（AD-17）；这里留 8 天，比商家平台 24 小时停止重放远得多。</li>
 * </ol>
 */
@Component
public class PurchaseOrderScanner {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderScanner.class);
    private static final int BATCH_LIMIT = 200;
    static final Duration IDEMPOTENCY_RETENTION = Duration.ofDays(8);

    private final PurchaseOrderRepository orders;
    private final PurchaseOrderService purchaseOrders;
    private final PurchaseOrderFulfillmentService fulfillment;
    private final PurchaseOrderIdempotencyStore idempotency;

    public PurchaseOrderScanner(PurchaseOrderRepository orders, PurchaseOrderService purchaseOrders,
            PurchaseOrderFulfillmentService fulfillment, PurchaseOrderIdempotencyStore idempotency) {
        this.orders = orders;
        this.purchaseOrders = purchaseOrders;
        this.fulfillment = fulfillment;
        this.idempotency = idempotency;
    }

    @Scheduled(fixedDelayString = "${petgo.merchant.po-expiry-scan-ms:60000}")
    public void scanExpiry() {
        int cancelled = 0;
        for (Long id : orders.findOverdueIds(Instant.now(), PageRequest.of(0, BATCH_LIMIT))) {
            try {
                if (purchaseOrders.expireOneOverdue(id)) {
                    cancelled++;
                }
            } catch (RuntimeException e) {
                log.warn("进货单超时取消失败 id={} cause={}", id, e.getClass().getSimpleName());
            }
        }
        if (cancelled > 0) {
            log.info("进货单付款超时取消并释放库存 count={}", cancelled);
        }
    }

    @Scheduled(fixedDelayString = "${petgo.merchant.po-fulfillment-scan-ms:600000}")
    public void scanFulfillment() {
        try {
            int n = fulfillment.autoDeliverOverdue(BATCH_LIMIT);
            if (n > 0) {
                log.info("进货单发货超 7 日自动置送达 count={}", n);
            }
        } catch (RuntimeException e) {
            log.warn("进货单自动置送达扫描失败 cause={}", e.getClass().getSimpleName());
        }
        try {
            int n = fulfillment.autoCompleteOverdue(BATCH_LIMIT);
            if (n > 0) {
                log.info("进货单送达超 7 日自动完成 count={}", n);
            }
        } catch (RuntimeException e) {
            log.warn("进货单自动完成扫描失败 cause={}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${petgo.merchant.po-idempotency-cleanup-ms:86400000}")
    public void cleanupIdempotency() {
        try {
            int n = idempotency.deleteCreatedBefore(Instant.now().minus(IDEMPOTENCY_RETENTION));
            if (n > 0) {
                log.info("进货单幂等记录清理 count={}", n);
            }
        } catch (RuntimeException e) {
            log.warn("进货单幂等记录清理失败 cause={}", e.getClass().getSimpleName());
        }
    }
}
