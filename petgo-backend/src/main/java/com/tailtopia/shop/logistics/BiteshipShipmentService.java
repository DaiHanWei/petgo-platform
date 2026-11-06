package com.tailtopia.shop.logistics;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.logistics.biteship.BiteshipClient;
import com.tailtopia.shared.logistics.biteship.BiteshipProperties;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipOrderSnapshot;
import com.tailtopia.shared.logistics.biteship.dto.CancelOutcome;
import com.tailtopia.shared.logistics.biteship.dto.CreateOutcome;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.logistics.BiteshipWaybillRequestAssembler.Assembly;
import com.tailtopia.shop.logistics.BiteshipWaybillRequestAssembler.Missing;
import com.tailtopia.shop.logistics.BiteshipWaybillRequestAssembler.Origin;
import com.tailtopia.shop.logistics.BiteshipWaybillRequestAssembler.Ready;
import com.tailtopia.shop.logistics.BiteshipWaybillRequestAssembler.SkuPackage;
import com.tailtopia.shop.logistics.event.ShopWaybillCancelRequestedEvent;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShipmentStatus;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.refund.RefundScope;
import com.tailtopia.shop.order.refund.ShopOrderRefund;
import com.tailtopia.shop.order.refund.ShopOrderRefundRepository;
import com.tailtopia.shop.order.refund.ShopWaybillCanceller;
import com.tailtopia.shop.order.repository.ShipmentRepository;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.ShopPaymentAlerts;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Biteship 下运单 / 重试 / 取消 / 快递原因重发（v1.4.2 shop Story 5.1 AC4 / AC5 / AC12，D-12、FR-172.6 / 172.8、P-7）。
 * 供后台线 Controller 与 stag 入口调用；同时实现 4.1 的 {@link ShopWaybillCanceller} 钩子（C-7）。
 *
 * <p>🔴 <b>外部调用不在持有行锁的事务内</b>（P-7）：短事务 ① 校验 + {@code waybill_attempts + 1} → 提交 → 事务外调
 * Biteship → 短事务 ② 重读订单、重新校验后落结果。本类方法<b>自身不开事务</b>（{@link TransactionTemplate}
 * {@code REQUIRES_NEW}），🔴 <b>调用方（后台 Controller）也不得在外层开事务</b>。
 *
 * <p>失败（Biteship 拒绝 / 结果不确定 / 前置数据缺失）<b>不抛异常</b>：订单写 {@code waybill_failed_at / waybill_fail_reason}，
 * 仍 {@code PENDING_SHIPMENT}，返回 {@link WaybillResult}。前置校验不过（状态、已有运单、存量订单、收件信息已清）抛 409，
 * 不写失败标记、不调 Biteship。
 *
 * <p>🔒 日志 / 审计只记订单 token / 展示号、referenceId、Biteship 订单 id、快递码、状态；不记收发件人任何字段、key。
 */
@Service
public class BiteshipShipmentService implements ShopWaybillCanceller {

    private static final Logger log = LoggerFactory.getLogger(BiteshipShipmentService.class);

    /** Biteship 取消原因四值（{@code cancellation_reason_code}）。 */
    public static final Set<String> CANCEL_REASONS =
            Set.of("change_courier", "pickup_delay", "change_address", "others");

    static final String RETRY_COUNTER_KEY = "shop:biteship:waybill-retry";
    static final String RETRY_ALERTED_KEY = "shop:biteship:waybill-retry-alerted";
    static final Duration RETRY_WINDOW = Duration.ofMinutes(5);
    static final long RETRY_ALERT_THRESHOLD = 10;

    /** 运单操作模式：新下单（待发货）/ 快递原因重发（已发货）。 */
    enum Mode {
        CREATE,
        RESHIP
    }

    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    private final ShipmentRepository shipments;
    private final ShopSkuRepository skus;
    private final ShippingSettingsRepository settings;
    private final ShopOrderRefundRepository refunds;
    private final BiteshipClient client;
    private final BiteshipProperties props;
    private final AdminAuditService audit;
    private final ShopPaymentAlerts alerts;
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired
    public BiteshipShipmentService(ShopOrderRepository orders, ShopOrderLineRepository orderLines,
            ShipmentRepository shipments, ShopSkuRepository skus, ShippingSettingsRepository settings,
            ShopOrderRefundRepository refunds, BiteshipClient client, BiteshipProperties props,
            AdminAuditService audit, ShopPaymentAlerts alerts, StringRedisTemplate redis,
            ApplicationEventPublisher events, PlatformTransactionManager txManager) {
        this(orders, orderLines, shipments, skus, settings, refunds, client, props, audit, alerts, redis, events,
                txManager, Clock.systemUTC());
    }

    BiteshipShipmentService(ShopOrderRepository orders, ShopOrderLineRepository orderLines,
            ShipmentRepository shipments, ShopSkuRepository skus, ShippingSettingsRepository settings,
            ShopOrderRefundRepository refunds, BiteshipClient client, BiteshipProperties props,
            AdminAuditService audit, ShopPaymentAlerts alerts, StringRedisTemplate redis,
            ApplicationEventPublisher events, PlatformTransactionManager txManager, Clock clock) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.shipments = shipments;
        this.skus = skus;
        this.settings = settings;
        this.refunds = refunds;
        this.client = client;
        this.props = props;
        this.audit = audit;
        this.alerts = alerts;
        this.redis = redis;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** V1.4.2 shop-admin Story 4.1 · AD-13：预估余额检查点（运单创建 / 取消后）。可缺省（单测不注入 = 不检查）。 */
    private com.tailtopia.shop.logistics.balance.BiteshipBalanceService balance;

    @Autowired(required = false)
    void setBalanceService(com.tailtopia.shop.logistics.balance.BiteshipBalanceService balance) {
        this.balance = balance;
    }

    /** 本事务内检查预估余额；提醒在事务提交后才发，失败只记日志（不影响运单）。 */
    private void checkBalance() {
        if (balance != null) {
            balance.checkQuietly();
        }
    }

    // ---------- 下运单 / 重试 / 重发 ----------

    /** 下运单（AC4）。前置不过抛 409；失败返回 {@code created=false}，不抛。 */
    public WaybillResult createWaybill(long orderId, Long actorAccountId) {
        return run(orderId, actorAccountId, Mode.CREATE);
    }

    /**
     * 重下运单（AC5.3）：同 {@link #createWaybill}，但计入全局重试计数（AC5.4：5 分钟内 ≥ 10 次告警一次）。
     * 🔴 上次失败若是 {@code UNKNOWN_RESULT}，Biteship 侧可能已建单 —— 每次重试换新 reference，盲目重试可能出两张运单。
     */
    public WaybillResult retryWaybill(long orderId, Long actorAccountId) {
        countRetry();
        return run(orderId, actorAccountId, Mode.CREATE);
    }

    /**
     * 快递原因重发（AC12，FR-172.8）：订单须 {@code SHIPPED}（或 {@code DELIVERED} 且带 {@code RETURNED} 异常）且有活动
     * Biteship 运单；同快递同服务、新 reference 建新运单，<b>成功后</b>旧运单 {@code supersede}（不调 Biteship 取消：
     * 已揽收的运单 Biteship 不允许取消）；订单状态不变。失败按 AC5 记失败、旧运单仍为活动运单（可再次重发）。
     */
    public WaybillResult reshipForCourierFault(long orderId, Long actorAccountId) {
        return run(orderId, actorAccountId, Mode.RESHIP);
    }

    private record Prepared(Ready ready, String referenceId, String courierCode, String serviceCode,
            String failReason, String displayNo) {
    }

    private record Settled(WaybillResult result, String orphanBiteshipOrderId) {
    }

    private WaybillResult run(long orderId, Long actor, Mode mode) {
        Prepared p = tx.execute(s -> prepare(orderId, mode, actor));
        if (p.failReason() != null) {
            return WaybillResult.failed(p.failReason());
        }
        CreateOutcome out = client.createOrder(p.ready().request());          // 🔴 事务外
        Settled settled;
        try {
            settled = tx.execute(s -> settle(orderId, mode, p, out, actor));
        } catch (RuntimeException e) {
            if (!(out instanceof CreateOutcome.Created c)) {
                throw e;
            }
            // 复审修复：Biteship 已建单但落库失败 → 按孤儿运单处理（事务外取消 + 告警），不留无主运单
            log.warn("Biteship 已建单但落库失败，按孤儿运单处理 order={} ref={} biteshipOrderId={} cause={}",
                    p.displayNo(), p.referenceId(), c.order().id(), e.getClass().getSimpleName());
            cancelOrphan(p.displayNo(), c.order().id(), "落库失败");
            return WaybillResult.failed("SETTLE_FAILED");
        }
        if (settled.orphanBiteshipOrderId() != null) {
            // 期间订单被取消 / 已有其他运单：立即取消刚建的 Biteship 运单（事务外）
            cancelOrphan(p.displayNo(), settled.orphanBiteshipOrderId(), "下运单期间订单已变化");
        }
        return settled.result();
    }

    /** 孤儿运单（已在 Biteship 建单、本地不建行）：事务外取消；取消不成功告警运营人工处理。🔒 只记展示号与 Biteship id。 */
    private void cancelOrphan(String displayNo, String biteshipOrderId, String why) {
        CancelOutcome c;
        try {
            c = client.cancelOrder(biteshipOrderId, "others");
        } catch (RuntimeException e) {
            c = new CancelOutcome.Unknown(e.getClass().getSimpleName());
        }
        log.warn("{}，已请求取消刚建的 Biteship 运单 order={} biteshipOrderId={} outcome={}", why, displayNo,
                biteshipOrderId, c.getClass().getSimpleName());
        if (!(c instanceof CancelOutcome.Cancelled)) {
            alerts.alert(com.tailtopia.admin.alert.OpsChannel.OPS, "[Biteship] 订单 " + displayNo + " " + why + "，运单 " + biteshipOrderId
                    + " 未能自动取消，请到 Biteship 后台人工取消");
        }
    }

    /** 短事务 ①：校验 + 组装；可下单时 {@code waybill_attempts + 1} 并随事务提交。 */
    private Prepared prepare(long orderId, Mode mode, Long actor) {
        ShopOrder order = requireOrder(orderId);
        precheck(order, mode);
        if (!client.isAvailable()) {
            return failNow(order, "NOT_CONFIGURED:BITESHIP", actor);
        }
        List<ShopOrderLine> lines = orderLines.findByOrderIdOrderByIdAsc(order.getId());
        Map<Long, SkuPackage> packages = skus.findAllById(lines.stream().map(ShopOrderLine::getSkuId).toList())
                .stream().collect(Collectors.toMap(ShopSku::getId,
                        k -> new SkuPackage(k.getPackageWeightG(), k.getPackageLengthCm(), k.getPackageWidthCm(),
                                k.getPackageHeightCm()), (a, b) -> a));
        Origin origin = settings.findById(ShippingSettings.SINGLETON_ID)
                .map(st -> new Origin(st.getOriginContactName(), st.getOriginContactPhone(), st.getOriginAddress(),
                        st.getOriginPostalCode()))
                .orElse(null);
        long lineGoodsRefunded = refunds.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()).stream()
                .filter(r -> r.getScope() == RefundScope.LINE).mapToLong(ShopOrderRefund::getGoodsRefund).sum();
        int attempt = order.getWaybillAttempts() + 1;
        Assembly a = BiteshipWaybillRequestAssembler.assemble(order, lines, packages, origin, lineGoodsRefunded,
                attempt, props.getDeliveryType());
        if (a instanceof Missing m) {
            return failNow(order, m.reason(), actor);
        }
        Ready ready = (Ready) a;
        order.incrementWaybillAttempts();
        orders.save(order);
        return new Prepared(ready, ready.request().referenceId(), order.getCourierCode(),
                order.getCourierServiceCode(), null, order.getDisplayNo());
    }

    private Prepared failNow(ShopOrder order, String reason, Long actor) {
        recordFailure(order, reason, null, actor);
        return new Prepared(null, null, null, null, reason, order.getDisplayNo());
    }

    /**
     * 短事务 ②：重读订单（🔴 {@code PESSIMISTIC_WRITE}，防并发下运单写两条活动运单；本段无外部调用）、重新校验，
     * 落运单或失败标记。
     */
    private Settled settle(long orderId, Mode mode, Prepared p, CreateOutcome out, Long actor) {
        ShopOrder order = orders.findByIdForUpdate(orderId).orElseThrow(() -> AppException.notFound("订单不存在"));
        Instant now = clock.instant();
        return switch (out) {
            case CreateOutcome.Created c -> {
                BiteshipOrderSnapshot snap = c.order();
                if (!stillValid(order, mode)) {
                    log.warn("下运单期间订单状态已变化，不建运单行 order={} status={} ref={} biteshipOrderId={}",
                            order.getDisplayNo(), order.getStatus(), p.referenceId(), snap.id());
                    yield new Settled(WaybillResult.failed("ORDER_CHANGED"), snap.id());
                }
                if (mode == Mode.RESHIP) {
                    for (Shipment old : shipments.findActiveByShopOrderId(order.getId())) {
                        if (old.isBiteship()) {
                            old.supersede(now);
                            shipments.save(old);
                        }
                    }
                }
                Shipment sh = Shipment.biteship(order.getId(), p.courierCode().toUpperCase(Locale.ROOT),
                        p.courierCode(), p.serviceCode(), snap.id(), p.referenceId(), snap.waybillId(),
                        snap.trackingId(), snap.price() == null ? 0L : snap.price(), p.ready().insuranceAmount(), now);
                sh.applyProviderStatus(snap.status(), now, null);
                sh.updateTrackingUrl(snap.link());
                shipments.save(sh);
                checkBalance();
                order.clearWaybillFailure();
                orders.save(order);
                audit.record(actor, AuditActions.SHOP_WAYBILL_CREATED, "SHOP_ORDER", order.getPublicToken(),
                        "%s：referenceId=%s；Biteship=%s；快递=%s/%s".formatted(
                                mode == Mode.RESHIP ? "快递原因重发运单" : "下运单", p.referenceId(), snap.id(),
                                p.courierCode(), p.serviceCode()));
                log.info("Biteship 运单已建 order={} ref={} biteshipOrderId={} courier={} status={} mode={}",
                        order.getDisplayNo(), p.referenceId(), snap.id(), p.courierCode(), snap.status(), mode);
                yield new Settled(WaybillResult.ok(), null);
            }
            case CreateOutcome.Rejected r -> {
                String reason = "REJECTED:" + (r.code() == null ? "UNKNOWN" : r.code());
                recordFailure(order, reason, p.referenceId(), actor);
                yield new Settled(WaybillResult.failed(reason), null);
            }
            case CreateOutcome.Unknown u -> {
                String reason = "UNKNOWN_RESULT:" + (u.cause() == null ? "UNKNOWN" : u.cause());
                log.warn("Biteship 下运单结果不确定 order={} ref={}：Biteship 侧可能已建单，重试前请在 Biteship 后台按 "
                        + "referenceId 核对", order.getDisplayNo(), p.referenceId());
                recordFailure(order, reason, p.referenceId(), actor);
                yield new Settled(WaybillResult.failed(reason), null);
            }
        };
    }

    private void recordFailure(ShopOrder order, String reason, String referenceId, Long actor) {
        order.recordWaybillFailure(clock.instant(), reason);
        orders.save(order);
        audit.record(actor, AuditActions.SHOP_WAYBILL_FAILED, "SHOP_ORDER", order.getPublicToken(),
                "下运单失败：原因=%s；referenceId=%s".formatted(reason, referenceId == null ? "—" : referenceId));
        log.warn("Biteship 下运单失败 order={} ref={} reason={}", order.getDisplayNo(), referenceId, reason);
    }

    /** 前置校验（AC4.1 / AC12.1）：任何一条不过都不调 Biteship、不写失败标记。 */
    private void precheck(ShopOrder order, Mode mode) {
        List<Shipment> active = shipments.findActiveByShopOrderId(order.getId());
        if (mode == Mode.CREATE) {
            if (order.getStatus() != ShopOrderStatus.PENDING_SHIPMENT) {
                throw AppException.conflict("该订单当前状态不可下运单：" + order.getStatus());
            }
            if (!active.isEmpty()) {
                throw AppException.conflict("该订单已有运单");
            }
        } else {
            if (!reshippable(order)) {
                throw AppException.conflict("该订单当前状态不可重发运单：" + order.getStatus());
            }
            if (active.stream().noneMatch(Shipment::isBiteship)) {
                throw AppException.conflict("该订单没有活动的 Biteship 运单");
            }
        }
        requireWaybillData(order);
    }

    /**
     * 下运单的数据前提（快递编码快照、收件信息未清）——与运单现状无关的那部分 precheck。5.2 揽收失败「重下运单」在取消旧运单
     * <b>之前</b>先调它（复审 5），避免旧运单已取消、新运单却因数据缺失建不出来。不过则 409。
     */
    public void requireWaybillData(ShopOrder order) {
        if (isBlank(order.getCourierCode()) || isBlank(order.getCourierServiceCode())) {
            throw AppException.conflict("存量订单请走手工运单");
        }
        AddressSnapshot ship = order.shipTo();
        if (isBlank(ship.receiverName()) || isBlank(ship.receiverPhone()) || isBlank(ship.addressLine())) {
            throw AppException.conflict("收件信息已清除，无法下运单");
        }
    }

    private boolean stillValid(ShopOrder order, Mode mode) {
        List<Shipment> active = shipments.findActiveByShopOrderId(order.getId());
        if (mode == Mode.CREATE) {
            return order.getStatus() == ShopOrderStatus.PENDING_SHIPMENT && active.isEmpty();
        }
        return reshippable(order) && active.stream().anyMatch(Shipment::isBiteship);
    }

    private static boolean reshippable(ShopOrder order) {
        return order.getStatus() == ShopOrderStatus.SHIPPED
                || (order.getStatus() == ShopOrderStatus.DELIVERED
                        && order.getLogisticsException() == LogisticsException.RETURNED);
    }

    /** AC5.4：全局重试计数；Redis 不可用只记 ERROR，不影响重试本身。 */
    void countRetry() {
        try {
            Long n = redis.opsForValue().increment(RETRY_COUNTER_KEY);
            if (n != null && n == 1L) {
                redis.expire(RETRY_COUNTER_KEY, RETRY_WINDOW);
            }
            if (n != null && n >= RETRY_ALERT_THRESHOLD) {
                Boolean first = redis.opsForValue().setIfAbsent(RETRY_ALERTED_KEY, "1", RETRY_WINDOW);
                if (Boolean.TRUE.equals(first)) {
                    log.error("Biteship 重下运单激增 count={} window=5m", n);
                    alerts.alert(com.tailtopia.admin.alert.OpsChannel.TECH, "[Biteship] 5 分钟内重下运单 " + n + " 次，请技术排查");
                }
            }
        } catch (RuntimeException e) {
            log.error("Biteship 重下运单计数失败（Redis 不可用，不影响重试）cause={}", e.getClass().getSimpleName());
        }
    }

    // ---------- 取消运单 ----------

    /**
     * 取消该单活动的 Biteship 运单（AC5.5，供 5.2 揽收失败处置）：无 → 幂等返回；{@code provider_status} 已是
     * {@code cancelled} → 只落本地；否则事务外 {@code cancelOrder}：{@code Cancelled} 落本地 {@code CANCELLED} /
     * {@code NotCancellable} 409 / {@code Unknown} 503 不改本地。
     *
     * @param reasonCode Biteship 四值之一，否则 422
     */
    public void cancelWaybill(long orderId, String reasonCode, Long actorAccountId) {
        if (reasonCode == null || !CANCEL_REASONS.contains(reasonCode)) {
            throw AppException.validation("取消原因只支持 change_courier / pickup_delay / change_address / others");
        }
        Shipment target = tx.execute(s -> activeBiteship(orderId));
        if (target == null) {
            return;
        }
        if ("cancelled".equalsIgnoreCase(target.getProviderStatus())) {
            tx.executeWithoutResult(s -> cancelLocally(target.getId(), reasonCode, actorAccountId));
            return;
        }
        // 复审修复：先登记「本地已发起取消」，Biteship 若先推 cancelled 则判为预期、不告警
        tx.executeWithoutResult(s -> shipments.findById(target.getId()).ifPresent(sh -> {
            sh.markCancelRequested(clock.instant());
            shipments.save(sh);
        }));
        CancelOutcome out = client.cancelOrder(target.getBiteshipOrderId(), reasonCode);     // 🔴 事务外
        switch (out) {
            case CancelOutcome.Cancelled c ->
                    tx.executeWithoutResult(s -> cancelLocally(target.getId(), reasonCode, actorAccountId));
            case CancelOutcome.NotCancellable n -> {
                // 5.2 复审 4：Biteship 明确拒绝 → 运单仍有效，复位「本地已发起取消」（Unknown 保留，结果不明）
                tx.executeWithoutResult(s -> shipments.findById(target.getId()).ifPresent(sh -> {
                    sh.clearCancelRequested(clock.instant());
                    shipments.save(sh);
                }));
                throw AppException.conflict(
                        "Biteship 运单已在 " + (n.providerStatus() == null ? "unknown" : n.providerStatus())
                                + "，无法取消");
            }
            case CancelOutcome.Unknown u -> {
                log.warn("Biteship 取消运单结果不确定 biteshipOrderId={} cause={}", target.getBiteshipOrderId(),
                        u.cause());
                throw AppException.serviceUnavailable("Biteship 暂不可用，运单未取消，请稍后重试");
            }
        }
    }

    private Shipment activeBiteship(long orderId) {
        return shipments.findActiveByShopOrderId(orderId).stream().filter(Shipment::isBiteship).findFirst()
                .orElse(null);
    }

    private void cancelLocally(long shipmentId, String reasonCode, Long actor) {
        Shipment s = shipments.findById(shipmentId).orElseThrow(() -> AppException.notFound("运单不存在"));
        Instant now = clock.instant();
        s.applyProviderStatus("cancelled", now, null);
        s.markCancelled(now);
        shipments.save(s);
        checkBalance();
        ShopOrder order = requireOrder(s.getShopOrderId());
        audit.record(actor, AuditActions.SHOP_WAYBILL_CANCELLED, "SHOP_ORDER", order.getPublicToken(),
                "取消运单：referenceId=%s；Biteship=%s；原因=%s".formatted(s.getReferenceId(), s.getBiteshipOrderId(),
                        reasonCode));
        log.info("Biteship 运单已取消 order={} biteshipOrderId={} reason={}", order.getDisplayNo(),
                s.getBiteshipOrderId(), reasonCode);
    }

    // ---------- 4.1 钩子（C-7） ----------

    /**
     * 平台原因整单取消（4.1 {@code ShopOrderRefundService#cancelWhole}）时取消未揽收的 Biteship 运单。
     *
     * <p>🔴 4.1 约定：调用方事务仍可能回滚 —— 这里<b>只登记意图</b>（发布 {@link ShopWaybillCancelRequestedEvent}），
     * 由 {@link BiteshipPlatformCancelListener} 在<b>事务提交后</b>异步调 Biteship 取消；失败由本类写 WARN + 运营提醒
     * （不再向调用方抛 —— 届时取消事务早已提交，与「不阻断取消与退款」一致）。
     */
    @Override
    public void cancelForPlatformCancel(ShopOrder order, Long actorAdminId) {
        events.publishEvent(new ShopWaybillCancelRequestedEvent(order.getId(), order.getDisplayNo(), actorAdminId));
    }

    /** 提交后执行：只取消 {@code CREATED}（未揽收）的活动 Biteship 运单；已揽收的不动（无从取消）。 */
    void cancelAfterPlatformCancel(ShopWaybillCancelRequestedEvent e) {
        Shipment target = tx.execute(s -> activeBiteship(e.orderId()));
        if (target == null || target.getStatus() != ShipmentStatus.CREATED) {
            return;
        }
        try {
            cancelWaybill(e.orderId(), "others", e.actorAdminId());
        } catch (RuntimeException ex) {
            log.warn("平台原因取消后取消 Biteship 运单失败（取消已生效）order={} cause={}", e.orderDisplayNo(),
                    ex.getClass().getSimpleName());
            alerts.alert(com.tailtopia.admin.alert.OpsChannel.OPS, "【电商】订单 " + e.orderDisplayNo()
                    + " 平台原因取消时 Biteship 运单取消失败，请到 Biteship 后台人工取消运单");
        }
    }

    // ---------- 内部 ----------

    private ShopOrder requireOrder(long orderId) {
        return orders.findById(orderId).orElseThrow(() -> AppException.notFound("订单不存在"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
