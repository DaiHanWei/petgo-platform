package com.tailtopia.shop.logistics;

import com.tailtopia.shared.logistics.biteship.BiteshipClient;
import com.tailtopia.shared.logistics.biteship.BiteshipProperties;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipOrderSnapshot;
import com.tailtopia.shop.logistics.BiteshipStatusMapper.Mapping;
import com.tailtopia.shop.logistics.domain.BiteshipWebhookEvent;
import com.tailtopia.shop.logistics.repository.BiteshipWebhookEventRepository;
import com.tailtopia.shop.order.domain.DeliverySource;
import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.Shipments;
import com.tailtopia.shop.order.domain.ShippedSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.event.ShopOrderShippedEvent;
import com.tailtopia.shop.order.repository.ShipmentRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.ShopPaymentAlerts;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Biteship 物流状态推进（v1.4.2 shop Story 5.1 AC7 / AC8，D-12：<b>不信任载荷</b>）。
 *
 * <ul>
 *   <li>{@link #process}：处理一条 Webhook 收件行 —— <b>事务外</b>回查 {@code getOrder}，再在短事务（REQUIRES_NEW）里
 *       {@link #applySnapshot}；载荷里的 status / waybill / price 一律不用（伪造载荷不改状态）；</li>
 *   <li>失败 → {@code attempts + 1}，留待 {@link BiteshipWebhookRetryJob} 重试；达上限：{@code UNKNOWN_ORDER} 置 processed、
 *       不告警；其余置 {@code alerted_at} + 告警，不再自动重试；</li>
 *   <li>{@link #refresh}：在途回查 / stag 推进用（同样事务外回查）。</li>
 * </ul>
 *
 * <p>订单推进全部经 {@link ShopOrder} 领域方法（{@code ShopOrderStatus.canTransitionTo}），不开新边；订单行
 * {@code @Version} 乐观锁冲突即本次失败、计入重试。
 *
 * <p>🔒 日志 / 告警只记订单展示号、Biteship 订单 id、状态；不记收件人任何字段。
 */
@Service
public class BiteshipTrackingService {

    private static final Logger log = LoggerFactory.getLogger(BiteshipTrackingService.class);

    static final String UNKNOWN_ORDER = "UNKNOWN_ORDER";

    private final BiteshipWebhookEventRepository inbox;
    private final ShipmentRepository shipments;
    private final ShopOrderRepository orders;
    private final BiteshipClient client;
    private final BiteshipProperties props;
    private final ShopPaymentAlerts alerts;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired
    public BiteshipTrackingService(BiteshipWebhookEventRepository inbox, ShipmentRepository shipments,
            ShopOrderRepository orders, BiteshipClient client, BiteshipProperties props, ShopPaymentAlerts alerts,
            ApplicationEventPublisher events, PlatformTransactionManager txManager) {
        this(inbox, shipments, orders, client, props, alerts, events, txManager, Clock.systemUTC());
    }

    BiteshipTrackingService(BiteshipWebhookEventRepository inbox, ShipmentRepository shipments,
            ShopOrderRepository orders, BiteshipClient client, BiteshipProperties props, ShopPaymentAlerts alerts,
            ApplicationEventPublisher events, PlatformTransactionManager txManager, Clock clock) {
        this.inbox = inbox;
        this.shipments = shipments;
        this.orders = orders;
        this.client = client;
        this.props = props;
        this.alerts = alerts;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    // ---------- Webhook 收件行 ----------

    /** 处理一条收件行（AC7.2）。🔴 本方法不开事务；回查在事务外。已处理 / 已告警 → 直接返回。 */
    public void process(long eventId) {
        BiteshipWebhookEvent ev = inbox.findById(eventId).orElse(null);
        if (ev == null || ev.isDone()) {
            return;
        }
        String biteshipOrderId = ev.getBiteshipOrderId();
        Optional<Shipment> shipment = biteshipOrderId == null ? Optional.empty()
                : shipments.findByBiteshipOrderId(biteshipOrderId);
        if (shipment.isEmpty()) {
            recordFailure(eventId, UNKNOWN_ORDER);
            return;
        }
        BiteshipOrderSnapshot snap;
        try {
            snap = client.getOrder(biteshipOrderId);                         // 🔴 事务外回查
        } catch (RuntimeException e) {
            recordFailure(eventId, e.getClass().getSimpleName());
            return;
        }
        long shipmentId = shipment.get().getId();
        try {
            tx.executeWithoutResult(s -> {
                applyInTx(shipmentId, snap);
                BiteshipWebhookEvent row = inbox.findById(eventId).orElseThrow();
                row.markProcessed(clock.instant());
                inbox.save(row);
            });
        } catch (RuntimeException e) {
            recordFailure(eventId, e.getClass().getSimpleName());
        }
    }

    /** 失败计数（AC7.3）。达上限：UNKNOWN_ORDER → processed + WARN（不告警）；其余 → alerted + ERROR + 告警。 */
    void recordFailure(long eventId, String error) {
        try {
            tx.executeWithoutResult(s -> {
                BiteshipWebhookEvent row = inbox.findById(eventId).orElse(null);
                if (row == null || row.isDone()) {
                    return;
                }
                int n = row.recordFailure(error);
                if (n >= Math.max(1, props.getWebhookMaxAttempts())) {
                    Instant now = clock.instant();
                    if (UNKNOWN_ORDER.equals(error)) {
                        row.markProcessed(now);
                        log.warn("Biteship Webhook 找不到对应运单，放弃 eventId={} biteshipOrderId={} attempts={}",
                                eventId, row.getBiteshipOrderId(), n);
                    } else {
                        row.markAlerted(now);
                        log.error("Biteship Webhook 处理连续失败，停止自动重试 eventId={} biteshipOrderId={} "
                                + "attempts={} lastError={}", eventId, row.getBiteshipOrderId(), n, error);
                        alerts.alert("[Biteship] Webhook 处理连续失败 " + n + " 次（Biteship 订单 "
                                + row.getBiteshipOrderId() + "，原因 " + error + "），请技术排查");
                    }
                }
                inbox.save(row);
            });
        } catch (RuntimeException e) {
            log.warn("Biteship Webhook 失败计数写入失败 eventId={} cause={}", eventId, e.getClass().getSimpleName());
        }
    }

    // ---------- 回查 / 推进 ----------

    /** 事务外回查后推进（在途回查 AC7.5 / stag 入口）。回查失败抛 {@code BiteshipException}。 */
    public boolean refresh(long shipmentId) {
        Shipment s = shipments.findById(shipmentId).orElse(null);
        if (s == null || s.getBiteshipOrderId() == null) {
            return false;
        }
        BiteshipOrderSnapshot snap = client.getOrder(s.getBiteshipOrderId());     // 🔴 事务外
        return applySnapshot(shipmentId, snap);
    }

    /** 在途回查一轮（AC7.5）：单条失败只 WARN 不中断。返回有变化的条数。 */
    public int pollInFlight(int limit) {
        int changed = 0;
        for (Shipment s : shipments.findBiteshipInFlight(PageRequest.of(0, Math.max(1, limit)))) {
            try {
                if (refresh(s.getId())) {
                    changed++;
                }
            } catch (RuntimeException e) {
                log.warn("Biteship 在途回查单条失败 shipmentId={} biteshipOrderId={} cause={}", s.getId(),
                        s.getBiteshipOrderId(), e.getClass().getSimpleName());
            }
        }
        return changed;
    }

    /** 在新短事务（REQUIRES_NEW）里应用快照。返回是否有变化（无变化不写库、不发事件）。 */
    public boolean applySnapshot(long shipmentId, BiteshipOrderSnapshot snap) {
        Boolean changed = tx.execute(s -> applyInTx(shipmentId, snap));
        return Boolean.TRUE.equals(changed);
    }

    /**
     * 应用快照（AC7.6 / AC8.2）。调用方须已在事务内。
     *
     * <ul>
     *   <li>幂等：状态、运单号、追踪 id、价格、链接都没变 → 不写库；快照 {@code updatedAt} 早于已存 {@code provider_status_at} → 不回退；</li>
     *   <li>总是更新运单原始状态 / 运单号 / 追踪 id / 承运成本 / 链接；用户侧状态仅映射非 null 时更新；</li>
     *   <li>只对<b>活动运单</b>推进订单，且只在状态变化时。</li>
     * </ul>
     */
    boolean applyInTx(long shipmentId, BiteshipOrderSnapshot snap) {
        Shipment s = shipments.findById(shipmentId).orElseThrow(() -> new IllegalStateException(UNKNOWN_ORDER));
        Instant now = clock.instant();
        Instant at = snap.updatedAt() != null ? snap.updatedAt() : now;
        if (snap.updatedAt() != null && s.getProviderStatusAt() != null
                && snap.updatedAt().isBefore(s.getProviderStatusAt())) {
            return false;   // 旧快照不回退
        }
        String raw = snap.status() == null ? null : snap.status().trim().toLowerCase(java.util.Locale.ROOT);
        boolean statusChanged = raw != null && !Objects.equals(raw, s.getProviderStatus());
        boolean waybillChanged = notBlank(snap.waybillId()) && !snap.waybillId().trim().equals(s.getWaybillId());
        boolean trackingChanged = notBlank(snap.trackingId()) && !snap.trackingId().trim().equals(s.getTrackingId());
        boolean priceChanged = snap.price() != null && snap.price() >= 0 && snap.price() != s.getCarrierCost();
        boolean linkChanged = Shipment.isHttps(snap.link()) && !snap.link().equals(s.getTrackingUrlColumn());
        if (!statusChanged && !waybillChanged && !trackingChanged && !priceChanged && !linkChanged) {
            return false;
        }
        Mapping mapping = BiteshipStatusMapper.map(raw);
        ShopOrder order = statusChanged && s.isActive()
                ? orders.findById(s.getShopOrderId()).orElseThrow(() -> new IllegalStateException("ORDER_NOT_FOUND"))
                : null;
        // 复审修复：退回 / 丢件 / 破损标记下的 delivered 被拦时，provider_status 与 user_status_key 都不写——
        // 否则 App 显示「已送达」，且此后回查判「无变化」永远不再处理（含 5.2 清标记后、AC12 重发新运单签收）。
        boolean deliverBlocked = order != null && mapping.action() == BiteshipStatusMapper.OrderAction.DELIVER
                && order.getLogisticsException() != null && order.getLogisticsException().blocksAutoDelivery();
        if (deliverBlocked) {
            // 拍板⑥：退回途中的 delivered 语义不明（可能是退回发件地），只告警、交运营判断；下次回查会重新判定
            log.warn("Biteship 回传签收但订单带物流异常，不推进、不记状态 order={} exception={} biteshipOrderId={}",
                    order.getDisplayNo(), order.getLogisticsException(), s.getBiteshipOrderId());
            alerts.alert("[Biteship] 订单 " + order.getDisplayNo() + " 带 " + order.getLogisticsException()
                    + " 异常时收到 delivered（Biteship 订单 " + s.getBiteshipOrderId() + "），请人工判断");
        } else if (statusChanged) {
            s.applyProviderStatus(raw, at, mapping.userStatusKey());
        }
        s.updateWaybill(snap.waybillId());
        s.updateTrackingId(snap.trackingId());
        s.updateCarrierCost(snap.price());
        s.updateTrackingUrl(snap.link());

        if (order != null && !deliverBlocked) {
            advanceOrder(order, s, mapping, raw, at, now);
            orders.save(order);
        }
        shipments.save(s);
        return true;
    }

    private void advanceOrder(ShopOrder order, Shipment s, Mapping mapping, String raw, Instant at, Instant now) {
        switch (mapping.action()) {
            case NONE -> {
                // 待揽收：订单不动
            }
            case SHIP -> {
                s.markPicked(at);
                if (order.getStatus() == ShopOrderStatus.PENDING_SHIPMENT) {
                    order.markShipped(at, ShippedSource.BITESHIP_PICKED);
                    events.publishEvent(new ShopOrderShippedEvent(order.getId(), order.getUserId(),
                            order.getPublicToken()));
                    log.info("Biteship 揽收推动订单发货 order={} biteshipOrderId={} status={}", order.getDisplayNo(),
                            s.getBiteshipOrderId(), raw);
                } else if (!isFulfilling(order.getStatus())) {
                    warnCancelledPicked(order, s, raw);
                }
            }
            case DELIVER -> deliver(order, s, raw, at);
            case FLAG_PICKUP_FAILED -> flag(order, s, LogisticsException.PICKUP_FAILED, raw, now);
            case FLAG_RETURNED -> flag(order, s, LogisticsException.RETURNED, raw, now);
            case ALERT_OPS -> {
                if ("cancelled".equals(raw) && expectedCancel(order, s)) {
                    // 复审修复：本地已发起取消（或订单已取消 / 退款中）时 Biteship 先推 cancelled —— 预期结果，直接落本地
                    s.markCancelled(now);
                    log.info("Biteship 回传 cancelled（预期取消）order={} biteshipOrderId={}", order.getDisplayNo(),
                            s.getBiteshipOrderId());
                    return;
                }
                if (!mapping.known()) {
                    log.warn("Biteship 未知运单状态 order={} biteshipOrderId={} status={}", order.getDisplayNo(),
                            s.getBiteshipOrderId(), raw);
                }
                alerts.alert("[Biteship] 订单 " + order.getDisplayNo() + " 运单状态 " + raw + "（Biteship 订单 "
                        + s.getBiteshipOrderId() + "），请处理");
            }
        }
    }

    /** 物流异常标记的拦截已在 {@link #applyInTx} 处理（被拦时不会进到这里）。 */
    private void deliver(ShopOrder order, Shipment s, String raw, Instant at) {
        s.markDelivered(at);
        ShopOrderStatus st = order.getStatus();
        if (st != ShopOrderStatus.PENDING_SHIPMENT && st != ShopOrderStatus.SHIPPED) {
            if (!isFulfilling(st)) {
                log.warn("Biteship 回传签收但订单不在履约中，不动订单 order={} status={} biteshipOrderId={}",
                        order.getDisplayNo(), st, s.getBiteshipOrderId());
            }
            return;
        }
        if (st == ShopOrderStatus.PENDING_SHIPMENT) {
            order.markShipped(at, ShippedSource.BITESHIP_PICKED);
        }
        List<Shipment> all = shipments.findByShopOrderIdOrderByIdAsc(order.getId());
        if (Shipments.allActiveDelivered(all)) {
            Instant last = Shipments.lastActiveDeliveredAt(all).orElse(at);
            order.markDelivered(last, DeliverySource.BITESHIP);
            log.info("Biteship 签收推动订单送达 order={} biteshipOrderId={}", order.getDisplayNo(),
                    s.getBiteshipOrderId());
        } else if (st == ShopOrderStatus.PENDING_SHIPMENT) {
            events.publishEvent(new ShopOrderShippedEvent(order.getId(), order.getUserId(), order.getPublicToken()));
        }
    }

    private void flag(ShopOrder order, Shipment s, LogisticsException type, String raw, Instant now) {
        if (order.flagLogisticsException(type, now)) {
            log.warn("Biteship 回传物流异常 order={} exception={} status={} biteshipOrderId={}", order.getDisplayNo(),
                    type, raw, s.getBiteshipOrderId());
            alerts.alert("[Biteship] 订单 " + order.getDisplayNo() + " 物流异常 " + type + "（Biteship 状态 " + raw
                    + "），请处理");
        }
    }

    private void warnCancelledPicked(ShopOrder order, Shipment s, String raw) {
        log.warn("已取消 / 退款中订单的运单被揽收 order={} status={} biteshipOrderId={} providerStatus={}",
                order.getDisplayNo(), order.getStatus(), s.getBiteshipOrderId(), raw);
        alerts.alert("[Biteship] 已取消订单 " + order.getDisplayNo() + " 的运单被揽收（Biteship 订单 "
                + s.getBiteshipOrderId() + "，状态 " + raw + "），请处理");
    }

    /** cancelled 是否预期：运单仍 CREATED，且本地已发起取消或订单已取消 / 退款中。 */
    private static boolean expectedCancel(ShopOrder order, Shipment s) {
        if (s.getStatus() != com.tailtopia.shop.order.domain.ShipmentStatus.CREATED) {
            return false;
        }
        ShopOrderStatus st = order.getStatus();
        return s.getCancelRequestedAt() != null || st == ShopOrderStatus.CANCELLED
                || st == ShopOrderStatus.REFUNDING || st == ShopOrderStatus.REFUNDED;
    }

    /** 已发货 / 已送达 / 已完成：订单已过发货节点，Biteship 后续回传不再动订单。 */
    private static boolean isFulfilling(ShopOrderStatus st) {
        return st == ShopOrderStatus.SHIPPED || st == ShopOrderStatus.DELIVERED || st == ShopOrderStatus.COMPLETED;
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }
}
