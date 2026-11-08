package com.tailtopia.shop.order.refund;

import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.RefundSucceededEvent;
import com.tailtopia.pay.refund.domain.PayoutChannel;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.schedule.ScheduleWindow;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.dto.RefundDestinationRequest;
import com.tailtopia.shop.order.dto.ShopOrderRefundView;
import com.tailtopia.shop.order.pricing.RefundOrderSnapshot;
import com.tailtopia.shop.order.pricing.RefundQuote;
import com.tailtopia.shop.order.pricing.ShopRefundCalculator;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.ShopPaymentAlerts;
import com.tailtopia.shop.returns.domain.CashDestination;
import com.tailtopia.shop.returns.domain.ReturnRequest;
import com.tailtopia.shop.service.InventoryMovementService;
import com.tailtopia.shop.service.ShopTokenGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 取消退款（v1.4.2 shop Story 4.1 AC3 ~ AC6，架构 D-14 / D-15；决策 S-2 / S-5 / S-8 / S-9 / C-7 / m1）。
 * 后台线 Controller、stag 入口与异常订单处置（{@code AdminShopOrderExceptionService}）调用本类。
 *
 * <p>🔴 <b>金额只在 {@link ShopRefundCalculator}</b>（P-1）：本类只装配快照、落单、记账、退币。
 * 🔴 <b>券只经 {@link CouponLedgerService}</b>（P-2）。🔴 <b>PawCoin 段只退回 PawCoin</b>：本类不存在把币段折成现金的路径。
 *
 * <p>公共步骤（同一事务）：订单行 {@code refunded_qty} → {@code recordRefund}（<b>先于</b>退币：超额即抛，不会先把币退出去）
 * → 建退款单 → 币段立即退回并发扣回事件 → 现金段 &gt; 0 待用户选去向，否则直接完成 → {@link #settleOrderIfDone}。
 *
 * <p>🔒 收款账号 / 户名不进日志、不进审计摘要、不下发明文（P-7 / NFR4）。
 */
@Service
public class ShopOrderRefundService {

    private static final Logger log = LoggerFactory.getLogger(ShopOrderRefundService.class);

    /** 现金段 7 天未选去向 → 运营提醒（AC6.2）。 */
    static final Duration DESTINATION_REMINDER_AFTER = Duration.ofDays(7);

    /** 加密列 255 字符的 base64 容量约 161 字节明文（12B IV + 16B tag），留余量。 */
    static final int PAYOUT_FIELD_MAX_BYTES = 150;

    static final int PAYOUT_FAIL_NOTE_MAX = 200;

    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    private final ShopOrderRefundRepository refunds;
    private final InventoryMovementService movements;
    private final PawCoinWalletService wallet;
    private final CouponLedgerService coupons;
    private final PawCoinConfigRepository pawcoinConfig;
    private final AdminAuditService audit;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;
    private final ShopTokenGenerator tokens;
    private final AccountDeletionRepository accountDeletions;
    private final ObjectProvider<ShopWaybillCanceller> waybillCancellers;
    private final ShopPaymentAlerts alerts;
    private final Clock clock;

    @Autowired
    public ShopOrderRefundService(ShopOrderRepository orders, ShopOrderLineRepository orderLines,
            ShopOrderRefundRepository refunds, InventoryMovementService movements, PawCoinWalletService wallet,
            CouponLedgerService coupons, PawCoinConfigRepository pawcoinConfig, AdminAuditService audit,
            NotificationService notifications, ApplicationEventPublisher events, ShopTokenGenerator tokens,
            AccountDeletionRepository accountDeletions, ObjectProvider<ShopWaybillCanceller> waybillCancellers,
            ShopPaymentAlerts alerts) {
        this(orders, orderLines, refunds, movements, wallet, coupons, pawcoinConfig, audit, notifications,
                events, tokens, accountDeletions, waybillCancellers, alerts, Clock.systemUTC());
    }

    ShopOrderRefundService(ShopOrderRepository orders, ShopOrderLineRepository orderLines,
            ShopOrderRefundRepository refunds, InventoryMovementService movements, PawCoinWalletService wallet,
            CouponLedgerService coupons, PawCoinConfigRepository pawcoinConfig, AdminAuditService audit,
            NotificationService notifications, ApplicationEventPublisher events, ShopTokenGenerator tokens,
            AccountDeletionRepository accountDeletions, ObjectProvider<ShopWaybillCanceller> waybillCancellers,
            ShopPaymentAlerts alerts, Clock clock) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.refunds = refunds;
        this.movements = movements;
        this.wallet = wallet;
        this.coupons = coupons;
        this.pawcoinConfig = pawcoinConfig;
        this.audit = audit;
        this.notifications = notifications;
        this.events = events;
        this.tokens = tokens;
        this.accountDeletions = accountDeletions;
        this.waybillCancellers = waybillCancellers;
        this.alerts = alerts;
        this.clock = clock;
    }

    /**
     * 处置结果。
     *
     * @param refund 本次生成的退款单（V1.4.2 shop-admin 5.1 起 {@code USER_FAULT_RETURNED} 先入库后取消，取消时即出单，恒非空）
     */
    public record Outcome(ShopOrder order, ShopOrderRefund refund) {

        public long coinRefunded() {
            return refund == null ? 0L : refund.getCoinRefund();
        }

        public long cashRefundDue() {
            return refund == null ? 0L : refund.getCashRefund();
        }
    }

    // ===================== AC3：取消与赔付 =====================

    /** 整单取消（非丢件破损原因）。 */
    @Transactional
    public Outcome cancelWhole(String orderToken, CancelReason reason, String note, Long actorAdminId) {
        return cancelWhole(orderToken, reason, null, note, actorAdminId);
    }

    /**
     * 整单取消（AC3 表前五行）。
     *
     * <ul>
     *   <li>{@code OUT_OF_STOCK / WAYBILL_FAILED}：{@code PENDING_SHIPMENT}，回补库存；</li>
     *   <li>{@code PICKUP_FAILED}：{@code PENDING_SHIPMENT} 或 {@code SHIPPED}，回补库存；</li>
     *   <li>{@code COURIER_FAULT / LOST_DAMAGED}：{@code SHIPPED}，不回补；</li>
     *   <li>{@code USER_FAULT_RETURNED}：{@code SHIPPED}，<b>须已按行确认入库</b>（{@code return_inbound_at} 非空，否则 409，
     *       V1.4.2 shop-admin 5.1 · O-3 先入库后取消）；直接出退款单（扣实付运费与保价、不补偿、不退券），
     *       <b>不回补库存</b>（已在 {@link #confirmReturnedInbound} 按行加过一次）。</li>
     * </ul>
     * 订单 → {@code REFUNDING}，全部退款单完成后 → {@code REFUNDED}；运单失败标记随取消清除（AD-9）。
     *
     * <p>C-7：平台原因先经 {@link ShopWaybillCanceller} 取消可取消的 Biteship 运单（在读写订单之前、不持锁）；
     * 失败只记 WARN 并发运营提醒，不阻断取消与退款。
     *
     * @param negotiatedAmount 仅未保价 {@code LOST_DAMAGED} 必填（这单总共赔多少）
     */
    @Transactional
    public Outcome cancelWhole(String orderToken, CancelReason reason, Long negotiatedAmount, String note,
            Long actorAdminId) {
        ShopOrder order = requireOrder(orderToken);
        requireWholeAllowed(order, reason);
        requireNotDeleted(order);
        if (reason == CancelReason.USER_FAULT_RETURNED && order.getReturnInboundAt() == null) {
            throw AppException.conflict("退回商品尚未确认入库，不能提交用户原因的取消")
                    .code("admin.err.v142.exception.inboundRequired");
        }
        Instant now = clock.instant();
        List<ShopOrderLine> lines = orderLines.findByOrderIdOrderByIdAsc(order.getId());
        // 全部校验（状态、注销、计算器）先于外部运单取消：校验失败时运单不应已被取消（复审修复）
        RefundQuote quote = quoteWhole(order, lines, reason, negotiatedAmount);
        if (reason.platformFault()) {
            cancelWaybillQuietly(order, actorAdminId);
        }
        if (restocksOnCancel(reason)) {
            restockRemaining(order, lines, actorAdminId);
            order = reloadOrder(order);   // 见 reloadOrder：回补清了一级缓存
            lines = orderLines.findByOrderIdOrderByIdAsc(order.getId());
        }
        fillRefundedQty(lines);
        order.markRefunding(reason, now);
        order.clearShortageFlag();
        order.clearWaybillFailure();
        ShopOrderRefund refund = createRefund(order, quote, actorAdminId, now);
        coupons.releaseOnCancel(order.getId(), reason.couponReleaseMode(), now);
        auditRefund(actorAdminId, orderToken, "整单取消", refund, note);
        notifyUser(order, withNote(reason == CancelReason.USER_FAULT_RETURNED
                ? "你的订单已取消，退回的商品已入库，我们已为你安排退款。"
                : "很抱歉，你的订单已被取消，我们已为你安排退款。", note));
        return new Outcome(order, refund);
    }

    // ===================== V1.4.2 shop-admin 5.1：试算（只读，与落库同一计算，AD-9 · G6） =====================

    /** 整单取消试算：与 {@link #cancelWhole} 同一 {@link #quoteWhole}；不改任何数据。 */
    @Transactional(readOnly = true)
    public RefundQuote previewWhole(String orderToken, CancelReason reason, Long negotiatedAmount) {
        ShopOrder order = requireOrder(orderToken);
        return quoteWhole(order, orderLines.findByOrderIdOrderByIdAsc(order.getId()), reason, negotiatedAmount);
    }

    /** 部分取消试算：与 {@link #cancelLine} 同一 {@code ShopRefundCalculator.partial}（最后一件 {@code becameWhole}）。 */
    @Transactional(readOnly = true)
    public RefundQuote previewLine(String orderToken, long lineId, int qty, CancelReason reason) {
        ShopOrder order = requireOrder(orderToken);
        return ShopRefundCalculator.partial(RefundOrderSnapshot.of(order, orderLines.findByOrderIdOrderByIdAsc(
                order.getId())), lineId, qty, reason);
    }

    /** 签收后赔付试算：与 {@link #compensate} 同一 {@code ShopRefundCalculator.compensation}。 */
    @Transactional(readOnly = true)
    public RefundQuote previewCompensate(String orderToken, Long negotiatedAmount) {
        ShopOrder order = requireOrder(orderToken);
        return ShopRefundCalculator.compensation(RefundOrderSnapshot.of(order,
                orderLines.findByOrderIdOrderByIdAsc(order.getId())), negotiatedAmount);
    }

    /** 整单退款金额的唯一装配点（落库与试算共用）。 */
    private static RefundQuote quoteWhole(ShopOrder order, List<ShopOrderLine> lines, CancelReason reason,
            Long negotiatedAmount) {
        return ShopRefundCalculator.whole(RefundOrderSnapshot.of(order, lines), reason, negotiatedAmount);
    }

    /**
     * 部分取消一行的若干件（AC3 末行）：仅 {@code PENDING_SHIPMENT}、仅平台原因；该行件数回补，订单状态不变；
     * 取消到最后一件（计算器 {@code becameWhole}）→ 按整单规则（{@code REFUNDING}、按原因退券、WHOLE 退款单）。
     * 部分取消不退券。
     */
    @Transactional
    public Outcome cancelLine(String orderToken, long lineId, int qty, CancelReason reason, String note,
            Long actorAdminId) {
        ShopOrder order = requireOrder(orderToken);
        if (order.getStatus() != ShopOrderStatus.PENDING_SHIPMENT) {
            throw statusNotAllowed(order);
        }
        if (!ShopRefundCalculator.isFullRemainingReason(reason)) {
            throw AppException.validation("部分取消只适用于平台原因").code("admin.err.orderRefund.reasonNotAllowed");
        }
        requireNotDeleted(order);
        List<ShopOrderLine> lines = orderLines.findByOrderIdOrderByIdAsc(order.getId());
        ShopOrderLine line = lines.stream()
                .filter(l -> l.getId() != null && l.getId() == lineId)
                .findFirst()
                .orElseThrow(() -> AppException.notFound("订单行不存在").code("admin.err.order.lineNotFound"));
        RefundQuote quote = ShopRefundCalculator.partial(RefundOrderSnapshot.of(order, lines), lineId, qty, reason);
        if (quote.becameWhole()) {
            cancelWaybillQuietly(order, actorAdminId);   // 将转整单：C-7 同整单取消（校验全部通过之后）
        }
        Instant now = clock.instant();

        // V1.4.2 shop-admin 7.2 · AD-11：未锁库存行（迟到款重下单锁不到）从没扣过库存，取消不回补
        if (!line.isStockUnlocked()) {
            movements.receiveReturn(line.getSkuId(), qty, order.getPublicToken(), wibToday(),
                    actorAdminId == null ? 0L : actorAdminId);
            order = reloadOrder(order);   // 见 reloadOrder：回补清了一级缓存
        }
        line = orderLines.findById(lineId).orElseThrow();
        line.addRefundedQty(qty);
        orderLines.save(line);
        if (quote.becameWhole()) {
            order.markRefunding(reason, now);
            order.clearShortageFlag();
            order.clearWaybillFailure();
        }
        ShopOrderRefund refund = createRefund(order, quote, actorAdminId, now);
        if (quote.becameWhole()) {
            coupons.releaseOnCancel(order.getId(), reason.couponReleaseMode(), now);
        }
        auditRefund(actorAdminId, orderToken, quote.becameWhole() ? "部分取消（最后一件，按整单）" : "部分取消",
                refund, note);
        notifyUser(order, withNote(quote.becameWhole()
                ? "很抱歉，你的订单已被取消，我们已为你安排退款。"
                : "很抱歉，你的订单中有商品无法发出，我们已取消该部分并为你安排退款。", note));
        return new Outcome(order, refund);
    }

    /** 按行入库结果：{@code complete} = 全部收齐（已写 {@code return_inbound_at}）。 */
    public record InboundOutcome(ShopOrder order, boolean complete) {
    }

    /**
     * 用户原因退回按行确认入库（V1.4.2 shop-admin Story 5.1 · AD-9 · O-3 / O-15；取代电商线 {@code confirmReturnInbound}）。
     *
     * <p>要求 {@code return_cause = USER_FAULT} 且订单仍 {@code SHIPPED}（先入库、后取消）；每行累计不超过
     * {@code qty − refunded_qty}；同事务调 {@code movements.receiveReturn}（🔴 库存只在这里加一次，取消时不再回补）；
     * 可分多次，全部收齐写 {@code return_inbound_at / return_inbound_by}。不出退款单、不动钱。
     *
     * @param qtyByLine 订单行 id → 本次入库件数（≥ 1）
     */
    @Transactional
    public InboundOutcome confirmReturnedInbound(String orderToken, java.util.Map<Long, Integer> qtyByLine,
            Long actorAdminId) {
        ShopOrder order = requireOrder(orderToken);
        if (order.getReturnCause() != com.tailtopia.shop.logistics.ReturnCause.USER_FAULT
                || order.getStatus() != ShopOrderStatus.SHIPPED) {
            throw AppException.conflict("该订单不是待入库的用户原因退回").code("admin.err.v142.inbound.notUserFault");
        }
        if (order.getReturnInboundAt() != null) {
            throw AppException.conflict("退回商品已全部入库").code("admin.err.v142.inbound.alreadyComplete");
        }
        if (qtyByLine == null || qtyByLine.isEmpty()) {
            throw AppException.validation("请填写入库件数").code("admin.err.v142.inbound.qtyPositive");
        }
        List<ShopOrderLine> lines = orderLines.findByOrderIdOrderByIdAsc(order.getId());
        java.util.Map<Long, ShopOrderLine> byId = new java.util.HashMap<>();
        lines.forEach(l -> byId.put(l.getId(), l));
        // 全部校验先于任何回补：不留下回补了一半的批次
        for (var e : qtyByLine.entrySet()) {
            ShopOrderLine line = byId.get(e.getKey());
            if (line == null) {
                throw AppException.notFound("订单行不存在").code("admin.err.order.lineNotFound");
            }
            Integer n = e.getValue();
            if (n == null || n <= 0) {
                throw AppException.validation("入库件数必须为正").code("admin.err.v142.inbound.qtyPositive");
            }
            if (n > line.pendingInboundQty()) {
                throw AppException.conflict("入库件数超过待入库件数").code("admin.err.v142.inbound.qtyExceeded");
            }
        }
        Instant now = clock.instant();
        StringBuilder detail = new StringBuilder();
        for (var e : qtyByLine.entrySet()) {
            ShopOrderLine line = byId.get(e.getKey());
            movements.receiveReturn(line.getSkuId(), e.getValue(), order.getPublicToken(), wibToday(),
                    actorAdminId == null ? 0L : actorAdminId);
            detail.append(detail.isEmpty() ? "" : "，").append("行").append(line.getId()).append("×").append(e.getValue());
        }
        // 🔴 receiveReturn 清了一级缓存（PR#49）：重读订单与订单行再改
        order = reloadOrder(order);
        lines = orderLines.findByOrderIdOrderByIdAsc(order.getId());
        boolean complete = true;
        for (ShopOrderLine line : lines) {
            Integer n = qtyByLine.get(line.getId());
            if (n != null) {
                line.addReturnInboundQty(n);
                orderLines.save(line);
            }
            if (line.pendingInboundQty() > 0) {
                complete = false;
            }
        }
        if (complete) {
            order.markReturnInbound(now, actorAdminId);
            orders.save(order);
        }
        audit.record(actorAdminId, AuditActions.SHOP_ORDER_RETURN_INBOUND_CONFIRMED, "SHOP_ORDER", orderToken,
                "按行确认退回入库：%s；库存回补；已收齐=%s".formatted(detail, complete ? "是" : "否"));
        return new InboundOutcome(order, complete);
    }

    /**
     * 签收 / 完成后才报破损的赔付（S-9）：{@code DELIVERED} 或 {@code COMPLETED}，<b>订单状态不变</b>、库存不动、
     * {@code refunded_qty} 不动；生成 COMPENSATION 退款单（丢件破损金额规则，{@code reason = LOST_DAMAGED}），退券。
     */
    @Transactional
    public Outcome compensate(String orderToken, Long negotiatedAmount, String note, Long actorAdminId) {
        ShopOrder order = requireOrder(orderToken);
        if (order.getStatus() != ShopOrderStatus.DELIVERED && order.getStatus() != ShopOrderStatus.COMPLETED) {
            throw statusNotAllowed(order);
        }
        requireNotDeleted(order);
        Instant now = clock.instant();
        List<ShopOrderLine> lines = orderLines.findByOrderIdOrderByIdAsc(order.getId());
        RefundQuote quote = ShopRefundCalculator.compensation(RefundOrderSnapshot.of(order, lines),
                negotiatedAmount);
        ShopOrderRefund refund = createRefund(order, quote, actorAdminId, now);
        coupons.releaseOnCancel(order.getId(), CancelReason.LOST_DAMAGED.couponReleaseMode(), now);
        auditRefund(actorAdminId, orderToken, "签收后破损赔付", refund, note);
        notifyUser(order, withNote("关于你反馈的商品破损，我们已为你安排赔付。", note));
        return new Outcome(order, refund);
    }

    /**
     * 迟到款退款（4.2 调用）：WHOLE / {@code LATE_PAYMENT} / 全现金退款单。
     * 🔴 <b>不调 {@code recordRefund}、不发扣回事件、不退券、不改订单状态</b>（迟到款不是这张订单的消费，见 4.2）。
     */
    @Transactional
    public ShopOrderRefund createLatePaymentRefund(ShopOrder order, long lateAmount, Long actorAdminId) {
        RefundQuote quote = ShopRefundCalculator.latePayment(lateAmount);
        ShopOrderRefund refund = refunds.save(ShopOrderRefund.create(tokens.generate(), order.getId(), quote,
                actorAdminId, clock.instant()));
        auditRefund(actorAdminId, order.getPublicToken(), "迟到款退款", refund, null);
        return refund;
    }

    // ===================== AC4：用户选择现金去向 =====================

    /**
     * 用户选择现金段去向（AC4）。订单非本人 / 退款单不属于该订单 → 404（不区分）；
     * 仅 {@code AWAITING_DESTINATION / PAYOUT_FAILED} 可选，否则 409 {@code refund-destination-locked}。
     *
     * <ul>
     *   <li>{@code TO_PAWCOIN}：现金段即时入账；{@code reason.compensate()} 为真 → 补偿（只对现金段）；→ {@code COMPLETED}；
     *       发 {@code SHOPREF:{t}:CASH_PAWCOIN} 扣回事件（补偿不计入）。</li>
     *   <li>{@code TO_BANK}：渠道费后端算（{@code cash ≤ fee} → 422），加密存账户 → {@code PAYOUT_PENDING}；此时不发事件。</li>
     * </ul>
     * 选择退款方式<b>不推送</b>（PRD 11-05）。
     */
    @Transactional
    public ShopOrderRefundView chooseDestination(long userId, String orderToken, String refundToken,
            RefundDestinationRequest req) {
        ShopOrder order = orders.findByPublicTokenAndUserId(orderToken, userId)
                .orElseThrow(() -> AppException.notFound("退款单不存在"));
        ShopOrderRefund refund = refunds.findByPublicTokenAndOrderId(refundToken, order.getId())
                .orElseThrow(() -> AppException.notFound("退款单不存在"));
        if (req == null) {
            throw AppException.validation("请选择退款方式");
        }
        CashDestination destination = parseDestination(req.destination());
        if (!refund.getStatus().canChooseDestination()) {
            throw AppException.refundDestinationLocked("该退款的退款方式已确定，不可再更改");
        }
        Instant now = clock.instant();
        long cash = refund.getCashRefund();
        String token = refund.getPublicToken();
        if (destination == CashDestination.TO_PAWCOIN) {
            long premium = refund.getReason().compensate() ? premiumFor(cash) : 0L;
            refund.chooseToPawcoin(premium, now);
            refunds.save(refund);
            wallet.credit(order.getUserId(), cash, PawCoinTxnType.REFUND, "SHOP_ORDER", order.getId(),
                    "shopref:" + token + ":cash-pawcoin");
            if (premium > 0) {
                wallet.credit(order.getUserId(), premium, PawCoinTxnType.BONUS, "SHOP_ORDER_COMPENSATION",
                        order.getId(), "shopref:" + token + ":compensation");
            }
            publishGrowthRefund(order, refund, "CASH_PAWCOIN", cash);
            settleOrderIfDone(order);
            log.info("取消退款去向=TO_PAWCOIN refund={} cash={} premium={}", token, cash, premium);
        } else {
            PayoutChannel channel = parseChannel(req.channel());
            String account = ReturnRequest.requirePayoutAccount(channel, req.account());
            String holder = req.accountHolderName() == null ? "" : req.accountHolderName().trim();
            if (holder.isEmpty()) {
                throw AppException.validation("请填写收款人姓名");
            }
            if (utf8Length(account) > PAYOUT_FIELD_MAX_BYTES || utf8Length(holder) > PAYOUT_FIELD_MAX_BYTES) {
                throw AppException.validation("收款账号或姓名过长");
            }
            ShopRefundCalculator.payoutNet(cash, channel);   // cash ≤ 渠道费 → 422
            refund.chooseToBank(channel, account, holder, now);
            refunds.save(refund);
            // 🔒 只记渠道，不记账号 / 户名
            log.info("取消退款去向=TO_BANK refund={} channel={}", token, channel);
        }
        return viewOf(refund);
    }

    // ===================== AC5：财务标记（V1.4.2 shop-admin 5.2 · AD-10：回填 / 失败原因 / 职责分离 / 线下结案） =====================

    /** V1.4.2 shop-admin 5.2 · O-12：职责分离违规审计（REQUIRES_NEW，被拦也留痕）。setter 注入，单测可缺省。 */
    private com.tailtopia.pay.refund.service.RefundAuditRecorder dutyAudit;
    /** V1.4.2 shop-admin 5.2 · AD-5：打款失败提醒客服群（提交后发送）。setter 注入，单测可缺省。 */
    private com.tailtopia.admin.alert.OpsAlertService opsAlerts;

    @Autowired(required = false)
    void setDutyAudit(com.tailtopia.pay.refund.service.RefundAuditRecorder dutyAudit) {
        this.dutyAudit = dutyAudit;
    }

    @Autowired(required = false)
    void setOpsAlerts(com.tailtopia.admin.alert.OpsAlertService opsAlerts) {
        this.opsAlerts = opsAlerts;
    }

    static final int PAYOUT_TXN_MAX = 64;
    static final int OFFLINE_NOTE_MAX = 200;

    /** stag / 存量调用：不回填流水号与截图（仍受职责分离约束）。 */
    @Transactional
    public ShopOrderRefundView markPaidOut(String refundToken, Long actorAdminId) {
        return markPaidOutInternal(refundToken, null, null, actorAdminId);
    }

    /**
     * 财务标记已打款（AC5.1 · V1.4.2 shop-admin 5.2 AC3）：{@code PAYOUT_PENDING → COMPLETED}，回填流水号（必填，≤ 64）与截图 key，
     * 发 {@code SHOPREF:{t}:CASH_BANK} 扣回事件（成长值此时扣回），结算订单。已完成 → 幂等返回，不重复发事件。
     * 🔴 职责分离：操作人 = 发起人 → 403（超管不豁免），违规审计独立提交。
     */
    @Transactional
    public ShopOrderRefundView markPaidOut(String refundToken, String txnNo, String proofKey, Long actorAdminId) {
        String t = txnNo == null ? "" : txnNo.trim();
        if (t.isEmpty()) {
            throw AppException.validation("请填写转账流水号").code("admin.err.v142.payout.txnRequired");
        }
        if (t.length() > PAYOUT_TXN_MAX) {
            throw AppException.validation("转账流水号过长").code("admin.err.v142.payout.txnTooLong");
        }
        return markPaidOutInternal(refundToken, t, proofKey, actorAdminId);
    }

    private ShopOrderRefundView markPaidOutInternal(String refundToken, String txnNo, String proofKey,
            Long actorAdminId) {
        ShopOrderRefund refund = requireRefund(refundToken);
        if (refund.getStatus() == ShopRefundStatus.COMPLETED && refund.getCashDestination() == CashDestination.TO_BANK) {
            return viewOf(refund);   // 已打款：幂等返回，不做职责分离判定、不写违规审计（复审修）
        }
        guardDutySeparation(refund, actorAdminId, "标记已打款");
        if (!refund.markPaidOut(clock.instant(), txnNo, proofKey, actorAdminId)) {
            return viewOf(refund);
        }
        refunds.save(refund);
        ShopOrder order = orders.findById(refund.getOrderId())
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
        publishGrowthRefund(order, refund, "CASH_BANK", refund.getCashRefund());
        settleOrderIfDone(order);
        // 🔒 审计只记账号末 4 位（O-22）；流水号不是账户信息，可记
        audit.record(actorAdminId, AuditActions.SHOP_ORDER_REFUND_PAYOUT_MARKED, "SHOP_ORDER_REFUND", refundToken,
                "标记已打款：渠道=%s；账号末4位=%s；现金段=%d；渠道费=%d；流水号=%s；截图=%s".formatted(
                        refund.getPayoutChannel(), refund.payoutAccountLast4(), refund.getCashRefund(),
                        refund.getPayoutFee(), txnNo == null ? "—" : txnNo, proofKey == null ? "无" : "有"));
        return viewOf(refund);
    }

    /** 同一流水号已被其他退款单用过（提示不拦截，AD-10）。 */
    @Transactional(readOnly = true)
    public boolean payoutTxnNoUsedElsewhere(String refundToken, String txnNo) {
        if (txnNo == null || txnNo.isBlank()) {
            return false;
        }
        return refunds.existsByPayoutTxnNoAndPublicTokenNot(txnNo.trim(), refundToken);
    }

    /** stag / 存量调用：原因记 OTHER。 */
    @Transactional
    public ShopOrderRefundView markPayoutFailed(String refundToken, String note, Long actorAdminId) {
        return markPayoutFailed(refundToken, PayoutFailReason.OTHER, note, actorAdminId);
    }

    /**
     * 财务标记打款失败（AC5.2 · V1.4.2 shop-admin 5.2 AC4）：{@code PAYOUT_PENDING → PAYOUT_FAILED}，记原因与备注（≤ 200 字，不得含账户）。
     * <b>不推送用户</b>；提交后提醒客服群联系用户（去重键 {@code payout-failed:{refundToken}:{payoutFailedAt}}）。用户可重选（AC4.3）。
     */
    @Transactional
    public ShopOrderRefundView markPayoutFailed(String refundToken, PayoutFailReason reason, String note,
            Long actorAdminId) {
        if (reason == null) {
            throw AppException.validation("请选择打款失败原因").code("admin.err.v142.payout.failReasonRequired");
        }
        ShopOrderRefund refund = requireRefund(refundToken);
        String trimmed = note == null ? null : note.trim();
        if (trimmed != null && trimmed.length() > PAYOUT_FAIL_NOTE_MAX) {
            throw AppException.validation("备注不能超过 200 字").code("admin.err.orderRefund.failNoteTooLong");
        }
        guardDutySeparation(refund, actorAdminId, "标记打款失败");
        Instant now = clock.instant();
        refund.markPayoutFailed(reason, trimmed == null || trimmed.isEmpty() ? null : trimmed, now, actorAdminId);
        refunds.save(refund);
        audit.record(actorAdminId, AuditActions.SHOP_ORDER_REFUND_PAYOUT_MARKED, "SHOP_ORDER_REFUND", refundToken,
                "标记打款失败：渠道=%s；账号末4位=%s；原因=%s".formatted(refund.getPayoutChannel(),
                        refund.payoutAccountLast4(), reason));
        if (opsAlerts != null) {
            String displayNo = orders.findById(refund.getOrderId()).map(ShopOrder::getDisplayNo).orElse("?");
            opsAlerts.send(com.tailtopia.admin.alert.OpsChannel.CS, "payout-failed:" + refundToken + ":" + now,
                    "【电商】订单 %s 的退款打款失败（原因：%s），用户需在 App 重新填写收款账户，请联系用户。".formatted(displayNo,
                            switch (reason) {
                                case ACCOUNT_INVALID -> "账户信息有误";
                                case TRANSFER_RETURNED -> "转账被退回";
                                case OTHER -> "其他";
                            }));
        }
        return viewOf(refund);
    }

    /**
     * 注销用户线下处理结案（V1.4.2 shop-admin 5.2 · O-17）：退款单所属用户已注销、单未完成 → {@code COMPLETED}（去向保持为空），
     * 须填说明（≤ 200 字，不得含账户），受职责分离约束，写审计 {@code SHOP_ORDER_REFUND_OFFLINE_CLOSED}；成长值不处理、不发通知。
     */
    @Transactional
    public ShopOrderRefundView closeOffline(String refundToken, String note, Long actorAdminId) {
        String n = note == null ? "" : note.trim();
        if (n.isEmpty()) {
            throw AppException.validation("请填写线下处理说明").code("admin.err.v142.payout.offlineNoteRequired");
        }
        if (n.length() > OFFLINE_NOTE_MAX) {
            throw AppException.validation("说明不能超过 200 字").code("admin.err.v142.payout.offlineNoteTooLong");
        }
        ShopOrderRefund refund = requireRefund(refundToken);
        ShopOrder order = orders.findById(refund.getOrderId())
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
        if (accountDeletions.findByUserId(order.getUserId()).isEmpty()) {
            throw AppException.conflict("该用户未注销，不能线下结案").code("admin.err.v142.payout.notDeletedUser");
        }
        guardDutySeparation(refund, actorAdminId, "线下结案");
        refund.closeOffline(n, clock.instant(), actorAdminId);
        refunds.save(refund);
        settleOrderIfDone(order);
        audit.record(actorAdminId, AuditActions.SHOP_ORDER_REFUND_OFFLINE_CLOSED, "SHOP_ORDER_REFUND", refundToken,
                "注销用户线下结案：现金段=%d；说明已记在退款单（不入审计，防账户信息进审计表）".formatted(refund.getCashRefund()));
        return viewOf(refund);
    }

    /**
     * 职责分离（O-12，照 {@code RefundService.guardDutySeparation}）：操作人 = 退款单发起人 → 违规审计（REQUIRES_NEW）+ 403；
     * 纯 id 相等判定，超管不豁免；发起人为空（系统生成）不拦截。
     */
    private void guardDutySeparation(ShopOrderRefund refund, Long actorAdminId, String action) {
        Long creator = refund.getCreatedByAdminId();
        if (creator != null && actorAdminId != null && creator.longValue() == actorAdminId.longValue()) {
            if (dutyAudit != null) {
                dutyAudit.recordShopRefundViolation(actorAdminId, refund.getPublicToken(),
                        "电商退款职责分离拦截：发起人不可" + action);
            }
            throw AppException.forbidden("退款职责分离：发起人不能标记打款结果").code("admin.err.v142.payout.dutySeparation");
        }
    }

    /** 当前操作人是否就是发起人（页面置灰用，与 {@link #guardDutySeparation} 同一判据）。 */
    public static boolean isInitiator(ShopOrderRefund refund, Long actorAdminId) {
        Long creator = refund.getCreatedByAdminId();
        return creator != null && actorAdminId != null && creator.longValue() == actorAdminId.longValue();
    }

    // ===================== 结算与读 =====================

    /**
     * 结算订单（建单、选 PawCoin、标记已打款三处调用，同一实现）：{@code REFUNDING} 且存在 WHOLE 退款单且该单
     * WHOLE / LINE 退款单<b>全部</b> {@code COMPLETED} → {@code REFUNDED}。COMPENSATION 与迟到款退款单不参与。
     */
    void settleOrderIfDone(ShopOrder order) {
        if (order.getStatus() != ShopOrderStatus.REFUNDING) {
            return;
        }
        List<ShopOrderRefund> own = refunds.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()).stream()
                .filter(r -> r.getScope() != RefundScope.COMPENSATION && r.getReason() != CancelReason.LATE_PAYMENT)
                .toList();
        boolean hasWhole = own.stream().anyMatch(r -> r.getScope() == RefundScope.WHOLE);
        boolean allDone = own.stream().allMatch(r -> r.getStatus() == ShopRefundStatus.COMPLETED);
        if (hasWhole && allDone) {
            order.transitionTo(ShopOrderStatus.REFUNDED);
            orders.save(order);
        }
    }

    /** 订单详情 {@code refunds[]}（AC6.1，按创建时间升序；6.2 复用）。 */
    @Transactional(readOnly = true)
    public List<ShopOrderRefundView> refundViewsOf(ShopOrder order) {
        List<ShopOrderRefund> list = refunds.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId());
        if (list.isEmpty()) {
            return List.of();
        }
        PawCoinConfig config = config();
        return list.stream().map(r -> viewOf(r, config)).toList();
    }

    /**
     * 订单详情收口（v1.4.2 shop Story 6.2 AC3）：退款单出参 + 行级（{@code LINE}）退款合计，<b>一次查询</b>。
     *
     * @param views              同 {@link #refundViewsOf}（按创建时间升序）
     * @param lineRefundTotals   订单行 id → 该行全部 {@code LINE} 级退款单 {@code total_refund} 之和（只搬运，不重算，P-1）
     */
    public record DetailRefunds(List<ShopOrderRefundView> views, java.util.Map<Long, Long> lineRefundTotals) {

        public static final DetailRefunds NONE = new DetailRefunds(List.of(), java.util.Map.of());
    }

    /** 见 {@link DetailRefunds}。 */
    @Transactional(readOnly = true)
    public DetailRefunds detailRefundsOf(ShopOrder order) {
        List<ShopOrderRefund> list = refunds.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId());
        if (list.isEmpty()) {
            return DetailRefunds.NONE;
        }
        PawCoinConfig config = config();
        java.util.Map<Long, Long> lineTotals = new java.util.HashMap<>();
        for (ShopOrderRefund r : list) {
            if (r.getScope() == RefundScope.LINE && r.getOrderLineId() != null) {
                lineTotals.merge(r.getOrderLineId(), r.getTotalRefund(), Long::sum);
            }
        }
        return new DetailRefunds(list.stream().map(r -> viewOf(r, config)).toList(), lineTotals);
    }

    /** 现金段超 7 天未选去向的退款单（只读，供后台线展示；不论是否已提醒）。 */
    @Transactional(readOnly = true)
    public List<ShopOrderRefund> findAwaitingDestinationOverdue() {
        return refunds.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(ShopRefundStatus.AWAITING_DESTINATION,
                clock.instant().minus(DESTINATION_REMINDER_AFTER));
    }

    // ===================== 内部 =====================

    /** 建单公共步骤：{@code recordRefund}（先于退币）→ 落单 → 币段立即退回 + 扣回事件 → 结算。 */
    private ShopOrderRefund createRefund(ShopOrder order, RefundQuote quote, Long actorAdminId, Instant now) {
        order.recordRefund(quote.totalRefund(), quote.coinRefund());
        orders.save(order);
        ShopOrderRefund refund = refunds.save(ShopOrderRefund.create(tokens.generate(), order.getId(), quote,
                actorAdminId, now));
        if (quote.coinRefund() > 0) {
            wallet.credit(order.getUserId(), quote.coinRefund(), PawCoinTxnType.REFUND, "SHOP_ORDER", order.getId(),
                    "shopref:" + refund.getPublicToken() + ":coin");
            publishGrowthRefund(order, refund, "COIN", quote.coinRefund());
        }
        settleOrderIfDone(order);
        return refund;
    }

    /**
     * 成长值扣回事件（AC5.3 / m1）：{@code refundKey = SHOPREF:{退款单 token}:{段}}，金额 = 本次退款额（不含补偿），
     * 分母 {@code order.paidTotal()}。🔴 {@code LATE_PAYMENT} 退款单任何环节都不发（AC5.4）。
     */
    private void publishGrowthRefund(ShopOrder order, ShopOrderRefund refund, String segment, long amount) {
        if (refund.getReason() == CancelReason.LATE_PAYMENT || amount <= 0) {
            return;
        }
        events.publishEvent(new RefundSucceededEvent("SHOPREF:" + refund.getPublicToken() + ":" + segment,
                order.getUserId(), PaymentPurpose.SHOP_ORDER, "SHOP_ORDER", String.valueOf(order.getId()), null,
                amount, order.paidTotal()));
    }

    private ShopOrder requireOrder(String orderToken) {
        return orders.findByPublicToken(orderToken)
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
    }

    /**
     * 库存回补后重读订单（PR#49 CI 修复）。
     *
     * <p>🔴 {@code InventoryMovementService.receiveReturn} → {@code SkuInventoryRepository.restock} 是
     * {@code @Modifying(flushAutomatically, clearAutomatically)}：先 flush 再<b>清空一级缓存</b>，之前读出的订单 / 订单行
     * 全部变成游离对象。继续改游离订单再 {@code save} 会 merge 出一份受管副本，第二次 {@code save}（结算 → REFUNDED）
     * 拿游离对象的旧 {@code version} 去 merge 已 flush 过的副本 → {@code ObjectOptimisticLockingFailureException}。
     * 因此回补之后一律重读，后续改动都作用在受管实体上（flushAutomatically 已把回补前的改动落库，重读不丢）。
     */
    private ShopOrder reloadOrder(ShopOrder order) {
        return orders.findById(order.getId())
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
    }

    private ShopOrderRefund requireRefund(String refundToken) {
        return refunds.findByPublicToken(refundToken)
                .orElseThrow(() -> AppException.notFound("退款单不存在").code("admin.err.orderRefund.notFound"));
    }

    /** 整单取消的「允许的订单状态」（AC3 表）。 */
    private static void requireWholeAllowed(ShopOrder order, CancelReason reason) {
        Set<ShopOrderStatus> allowed = switch (reason) {
            case OUT_OF_STOCK, WAYBILL_FAILED -> Set.of(ShopOrderStatus.PENDING_SHIPMENT);
            case PICKUP_FAILED -> Set.of(ShopOrderStatus.PENDING_SHIPMENT, ShopOrderStatus.SHIPPED);
            case COURIER_FAULT, LOST_DAMAGED, USER_FAULT_RETURNED -> Set.of(ShopOrderStatus.SHIPPED);
            default -> throw AppException.validation("该取消原因不适用于已付款订单")
                    .code("admin.err.orderRefund.reasonNotAllowed");
        };
        if (!allowed.contains(order.getStatus())) {
            throw statusNotAllowed(order);
        }
    }

    private static AppException statusNotAllowed(ShopOrder order) {
        return AppException.conflict("当前订单状态不允许该操作：" + order.getStatus())
                .code("admin.err.orderRefund.statusNotAllowed", order.getStatus());
    }

    /**
     * 注销防御（D1/D2，同 3.2 {@code releaseUnpaid}）：已注销账号不再生成退款单 —— 否则会给已注销账号重建钱包、
     * 留下用户永远无法选择去向的现金段。由运营线下处理。
     */
    private void requireNotDeleted(ShopOrder order) {
        if (accountDeletions.findByUserId(order.getUserId()).isPresent()) {
            throw AppException.conflict("该用户已注销，请线下处理退款").code("admin.err.orderRefund.userDeleted");
        }
    }

    /** 平台原因中回补库存的三类（快递原因 / 丢件破损不回补，用户原因在入库确认时回补）。 */
    private static boolean restocksOnCancel(CancelReason reason) {
        return reason == CancelReason.OUT_OF_STOCK || reason == CancelReason.WAYBILL_FAILED
                || reason == CancelReason.PICKUP_FAILED;
    }

    /** 🔴 回补走退货入库批次：「原因」就是原订单号（沿用 {@code AdminShopOrderExceptionService.restock}）。 */
    private void restockRemaining(ShopOrder order, List<ShopOrderLine> lines, Long actorAdminId) {
        for (ShopOrderLine line : lines) {
            int remaining = line.getQty() - line.getRefundedQty();
            if (remaining > 0 && !line.isStockUnlocked()) {   // 7.2 · AD-11：未锁库存行不回补
                movements.receiveReturn(line.getSkuId(), remaining, order.getPublicToken(), wibToday(),
                        actorAdminId == null ? 0L : actorAdminId);
            }
        }
    }

    /** WHOLE：各行 {@code refunded_qty} 补齐到 {@code qty}（沿用 {@code cancelWholeOrder} 写法）。 */
    private void fillRefundedQty(List<ShopOrderLine> lines) {
        for (ShopOrderLine line : lines) {
            int remaining = line.getQty() - line.getRefundedQty();
            if (remaining > 0) {
                line.addRefundedQty(remaining);
                orderLines.save(line);
            }
        }
    }

    /** C-7：取消 Biteship 运单；失败只 WARN + 运营提醒，不阻断。5.1 之前无实现 → 跳过。 */
    private void cancelWaybillQuietly(ShopOrder order, Long actorAdminId) {
        ShopWaybillCanceller canceller = waybillCancellers.getIfAvailable();
        if (canceller == null) {
            return;
        }
        try {
            canceller.cancelForPlatformCancel(order, actorAdminId);
        } catch (RuntimeException e) {
            log.warn("平台原因取消前取消 Biteship 运单失败（不阻断取消）order={} cause={}", order.getDisplayNo(),
                    e.getClass().getSimpleName());
            alerts.alert(com.tailtopia.admin.alert.OpsChannel.OPS, "【电商】订单 " + order.getDisplayNo()
                    + " 平台原因取消时 Biteship 运单取消失败，请到 Biteship 后台人工取消运单");
        }
    }

    private void auditRefund(Long actorAdminId, String orderToken, String action, ShopOrderRefund r, String note) {
        audit.record(actorAdminId, AuditActions.SHOP_ORDER_REFUND_HANDLED, "SHOP_ORDER", orderToken,
                "%s：退款单=%s；范围=%s；原因=%s；退款=%d（币 %d / 现金 %d；商品 %d / 运费 %d / 保价 %d）；说明=%s".formatted(
                        action, r.getPublicToken(), r.getScope(), r.getReason(), r.getTotalRefund(),
                        r.getCoinRefund(), r.getCashRefund(), r.getGoodsRefund(), r.getShippingRefund(),
                        r.getInsuranceRefund(), safeNote(note)));
    }

    /** 站内信附上运营填写的说明（异常订单处置「原因会一并告知用户」的既有约定）。 */
    private static String withNote(String body, String note) {
        return note == null || note.isBlank() ? body : body + "说明：" + note.trim();
    }

    private static String safeNote(String note) {
        return note == null || note.isBlank() ? "—" : note.trim();
    }

    /** 🔒 站内信只说原因与致歉，不带金额明细、不带收件信息；发送失败不回滚（沿用异常订单处置）。 */
    private void notifyUser(ShopOrder order, String body) {
        try {
            notifications.send(order.getUserId(), NotificationType.SHOP_ORDER_EXCEPTION, "订单有变动", body,
                    NotificationType.SHOP_ORDER_EXCEPTION.name(), order.getPublicToken());
        } catch (RuntimeException e) {
            log.warn("取消退款站内信发送失败（不回滚）order={} cause={}", order.getPublicToken(),
                    e.getClass().getSimpleName());
        }
    }

    private PawCoinConfig config() {
        return pawcoinConfig.findAll().stream().findFirst().orElse(null);
    }

    private long premiumFor(long cash) {
        PawCoinConfig config = config();
        return config == null ? 0L
                : ShopRefundCalculator.compensationPremium(cash, config.getCompensationPremiumRate(),
                        config.getCompensationPremiumCap());
    }

    private ShopOrderRefundView viewOf(ShopOrderRefund r) {
        return viewOf(r, config());
    }

    private static ShopOrderRefundView viewOf(ShopOrderRefund r, PawCoinConfig config) {
        long ifPawcoin = 0L;
        if (config != null && r.getStatus().canChooseDestination() && r.getReason().compensate()) {
            ifPawcoin = ShopRefundCalculator.compensationPremium(r.getCashRefund(),
                    config.getCompensationPremiumRate(), config.getCompensationPremiumCap());
        }
        return ShopOrderRefundView.of(r, ifPawcoin);
    }

    private static CashDestination parseDestination(String raw) {
        if (raw != null) {
            for (CashDestination d : CashDestination.values()) {
                if (d.name().equalsIgnoreCase(raw.trim())) {
                    return d;
                }
            }
        }
        throw AppException.validation("请选择退款方式");
    }

    private static PayoutChannel parseChannel(String raw) {
        if (raw == null || raw.isBlank()) {
            throw AppException.validation("请选择收款渠道");
        }
        for (PayoutChannel c : PayoutChannel.values()) {
            if (c.name().equalsIgnoreCase(raw.trim())) {
                return c;
            }
        }
        throw AppException.validation("收款渠道不支持");
    }

    /** 入库日期按 WIB 日界（运营墙上时间口径，{@link ScheduleWindow#WIB}）。 */
    private LocalDate wibToday() {
        return LocalDate.ofInstant(clock.instant(), ScheduleWindow.WIB);
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
