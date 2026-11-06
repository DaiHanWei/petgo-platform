package com.tailtopia.shop.logistics;

import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShipmentStatus;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShipmentRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.ShopPaymentAlerts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 物流停滞检测（v1.4.2 shop Story 5.2 AC3，D-13、PRD §3.10 ③）：每小时一轮，Biteship 活动运单超过
 * {@code petgo.shop.logistics.stall-after-hours}（默认 72）小时没有新状态 → 订单标 {@code STALLED}。
 *
 * <ul>
 *   <li>防重入：外层事务内 {@code pg_try_advisory_xact_lock(hashtext('logistics_stall'))}，拿不到即跳过（照
 *       {@code TopicHeatJob}；本任务<b>无外部调用</b>，可以用 xact 锁）；</li>
 *   <li>候选 ≤ 500 单（{@link ShipmentRepository#findStallCandidateOrderIds}）；逐单独立短事务（REQUIRES_NEW）重新校验后写
 *       {@link ShopOrder#flagLogisticsException}，单笔失败只 WARN；</li>
 *   <li>🔴 判据 {@code max(provider_status_at, timer_resumed_at) < cutoff}：72 小时整不标、超过才标，清除后重新计时；
 *       手工运单（{@code MANUAL}）没有物流状态来源，不参与；已有其他处置的 Biteship 状态不参与；</li>
 *   <li>用户侧不提示（{@code hasUserVisibleDeliveryIssue} 不含 STALLED）；整轮新标 &gt; 0 时发一条飞书汇总提醒运营追件
 *       （只列展示号，最多 20 个，其余写「等 N 单」）。</li>
 * </ul>
 */
@Component
public class LogisticsStallJob {

    private static final Logger log = LoggerFactory.getLogger(LogisticsStallJob.class);

    static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_xact_lock(hashtext('logistics_stall'))";
    static final int BATCH = 500;
    static final int ALERT_LIST_MAX = 20;
    /** 这些 Biteship 状态已有其他处置（告警 / 退回 / 揽收失败），不算停滞。 */
    static final Set<String> HANDLED_PROVIDER_STATUSES =
            Set.of("cancelled", "rejected", "disposed", "returned", "courier_not_found");

    /** 一轮结果：{@code skipped} = 没拿到锁；{@code flagged} = 新标展示号。 */
    public record Result(boolean skipped, List<String> flagged) {
    }

    private final JdbcTemplate jdbc;
    private final ShipmentRepository shipments;
    private final ShopOrderRepository orders;
    private final ShopPaymentAlerts alerts;
    private final TransactionTemplate outerTx;
    private final TransactionTemplate itemTx;
    private final Duration stallAfter;
    private final Clock clock;

    @Autowired
    public LogisticsStallJob(JdbcTemplate jdbc, ShipmentRepository shipments, ShopOrderRepository orders,
            ShopPaymentAlerts alerts, PlatformTransactionManager txManager,
            @Value("${petgo.shop.logistics.stall-after-hours:72}") long stallAfterHours) {
        this(jdbc, shipments, orders, alerts, txManager, Duration.ofHours(stallAfterHours), Clock.systemUTC());
    }

    LogisticsStallJob(JdbcTemplate jdbc, ShipmentRepository shipments, ShopOrderRepository orders,
            ShopPaymentAlerts alerts, PlatformTransactionManager txManager, Duration stallAfter, Clock clock) {
        this.jdbc = jdbc;
        this.shipments = shipments;
        this.orders = orders;
        this.alerts = alerts;
        this.outerTx = new TransactionTemplate(txManager);
        this.outerTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.itemTx = new TransactionTemplate(txManager);
        this.itemTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.stallAfter = stallAfter;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${petgo.shop.logistics.stall-scan-ms:3600000}",
            initialDelayString = "${petgo.shop.logistics.stall-scan-initial-delay-ms:300000}")
    public void scheduled() {
        try {
            Result r = run();
            if (r.skipped()) {
                log.info("物流停滞检测跳过：锁被占用");
            } else {
                log.info("物流停滞检测一轮 flagged={}", r.flagged().size());
            }
        } catch (RuntimeException e) {
            log.warn("物流停滞检测整轮失败 cause={}", e.getClass().getSimpleName());
        }
    }

    /** 一轮检测。 */
    public Result run() {
        Result r = outerTx.execute(status -> {
            Boolean locked = jdbc.queryForObject(TRY_LOCK_SQL, Boolean.class);
            if (!Boolean.TRUE.equals(locked)) {
                return new Result(true, List.of());
            }
            Instant now = clock.instant();
            Instant cutoff = now.minus(stallAfter);
            List<String> flagged = new ArrayList<>();
            for (Long orderId : shipments.findStallCandidateOrderIds(cutoff, BATCH)) {
                try {
                    String displayNo = itemTx.execute(s -> flagOne(orderId, cutoff, now));
                    if (displayNo != null) {
                        flagged.add(displayNo);
                    }
                } catch (RuntimeException e) {
                    log.warn("物流停滞标记单笔失败 orderId={} cause={}", orderId, e.getClass().getSimpleName());
                }
            }
            return new Result(false, List.copyOf(flagged));
        });
        if (r != null && !r.flagged().isEmpty()) {
            alerts.alert(summary(r.flagged(), stallAfter.toHours()));
        }
        return r == null ? new Result(true, List.of()) : r;
    }

    /** 单笔（独立短事务）：重新校验后写 STALLED。返回新标订单展示号；不符合返回 null。 */
    String flagOne(long orderId, Instant cutoff, Instant now) {
        ShopOrder order = orders.findById(orderId).orElse(null);
        if (order == null || order.getLogisticsException() != null
                || (order.getStatus() != ShopOrderStatus.PENDING_SHIPMENT
                        && order.getStatus() != ShopOrderStatus.SHIPPED)) {
            return null;
        }
        // 复审 2：清除停滞（运营 / 新物流状态）后从 timer_resumed_at 重新计 72 小时，否则清除后一小时内又被重标
        if (order.getTimerResumedAt() != null && !order.getTimerResumedAt().isBefore(cutoff)) {
            return null;
        }
        boolean stalled = shipments.findActiveByShopOrderId(orderId).stream().anyMatch(s -> isStalled(s, cutoff));
        if (!stalled || !order.flagLogisticsException(LogisticsException.STALLED, now)) {
            return null;
        }
        orders.save(order);
        log.warn("物流停滞标记 order={} after={}h", order.getDisplayNo(), stallAfter.toHours());
        return order.getDisplayNo();
    }

    /** 🔴 只认 Biteship 活动运单；{@code provider_status_at} 严格早于 cutoff（72 小时整不标）。 */
    static boolean isStalled(Shipment s, Instant cutoff) {
        if (!s.isBiteship() || !s.isActive()) {
            return false;
        }
        if (s.getStatus() != ShipmentStatus.CREATED && s.getStatus() != ShipmentStatus.SHIPPED) {
            return false;
        }
        if (s.getProviderStatusAt() == null || !s.getProviderStatusAt().isBefore(cutoff)) {
            return false;
        }
        String ps = s.getProviderStatus();
        return ps == null || !HANDLED_PROVIDER_STATUSES.contains(ps.toLowerCase(Locale.ROOT));
    }

    static String summary(List<String> displayNos, long hours) {
        int n = displayNos.size();
        String head = String.join("、", displayNos.subList(0, Math.min(n, ALERT_LIST_MAX)));
        String tail = n > ALERT_LIST_MAX ? " 等共 " + n + " 单" : "";
        return "[物流] " + n + " 单 Biteship 运单超过 " + hours + " 小时无新物流状态（已标停滞），请到 Biteship 追件："
                + head + tail;
    }
}
