package com.tailtopia.admin.shop.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.ShopOrderRefundService;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 异常订单处置（Story 4.4，AB-11D / S-3）。
 *
 * <p>⚠️ <b>真正的超卖来源是实物比账面少（盘点 / 报损没跟上），不是并发</b> —— 并发已由 Story 1.2 的
 * 条件原子写解决。这句话不只是注释：异常订单视图的说明文案也必须写明它，
 * 否则运营会一直以为这是系统 bug 而不去查自己刚做的那次盘点。
 *
 * <p>🔴 <b>判据 = 运营手工标记缺货</b>（{@link #flagShortage}，V20260918_2243），不是库存数字：
 * 库存不变式 {@code locked <= actual} 让「账面缺货」在库里不可能出现（盘点低于锁定量直接被拒），
 * 原判据 {@code actual < locked} 因此永远不成立、工作台恒空。真实缺货只在拣货时被人发现。
 *
 * <p>🔴 <b>S-3：运营手工选单取消，不做自动取消。</b>SKU ≤ 30、单量低，手工完全可行；
 * 自动取消会误杀大客户 —— 而被误杀的那一单往往正是最该保住的那一单。
 *
 * <p>🔴 <b>v1.4.2 shop Story 4.1：处置①② 的钱全部改走 {@link ShopOrderRefundService}</b>（D-15）：
 * 金额只在 {@code ShopRefundCalculator}，币段即时退回，现金段生成取消退款单由用户选去向（PawCoin / 线下打款）；
 * 平台责任补偿只对「现金段选退为 PawCoin」的金额发放（读 {@code compensation_premium_rate}，<b>不是</b> {@code premiumRate}）。
 * 本类不再直接动钱包、不再发扣回事件。用户站内信由退款服务发送（每次处置一条）。
 */
@Service
public class AdminShopOrderExceptionService {

    private static final Logger log =
            LoggerFactory.getLogger(AdminShopOrderExceptionService.class);

    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    private final com.tailtopia.shop.repository.SkuInventoryRepository inventory;
    private final ShopOrderRefundService refundService;
    private final AdminAuditService audit;
    private final NotificationService notifications;
    /** V1.4.2 shop-admin Story 5.1：赔付单是否已有（丢件破损来源判定）。 */
    private final com.tailtopia.shop.order.refund.ShopOrderRefundRepository refunds;
    /** O-26：已注销用户的异常订单不进本队列（转 5.2「注销用户·线下处理」）。 */
    private final com.tailtopia.account.repository.AccountDeletionRepository accountDeletions;
    /** O-21：有活动运单的订单不能部分取消。 */
    private final com.tailtopia.shop.order.repository.ShipmentRepository shipments;

    @org.springframework.beans.factory.annotation.Autowired
    public AdminShopOrderExceptionService(ShopOrderRepository orders,
            ShopOrderLineRepository orderLines,
            com.tailtopia.shop.repository.SkuInventoryRepository inventory,
            ShopOrderRefundService refundService,
            AdminAuditService audit, NotificationService notifications,
            com.tailtopia.shop.order.refund.ShopOrderRefundRepository refunds,
            com.tailtopia.account.repository.AccountDeletionRepository accountDeletions,
            com.tailtopia.shop.order.repository.ShipmentRepository shipments) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.inventory = inventory;
        this.refundService = refundService;
        this.audit = audit;
        this.notifications = notifications;
        this.refunds = refunds;
        this.accountDeletions = accountDeletions;
        this.shipments = shipments;
    }

    // ---------- V1.4.2 shop-admin Story 5.1：队列来源 / 原因类别 / 试算 ----------

    /** 订单的队列来源（AD-9 一个函数）；不在队列 → 空。 */
    @Transactional(readOnly = true)
    public java.util.Optional<ExceptionSource> source(ShopOrder order) {
        boolean le = order.getLogisticsException() == com.tailtopia.shop.order.domain.LogisticsException.LOST
                || order.getLogisticsException() == com.tailtopia.shop.order.domain.LogisticsException.DAMAGED;
        boolean hasCompensation = le && refunds.existsByOrderIdAndScope(order.getId(),
                com.tailtopia.shop.order.refund.RefundScope.COMPENSATION);
        return ExceptionSource.of(new ExceptionSource.Facts(order.getStatus(), order.isShortageFlagged(),
                order.getWaybillFailedAt() != null, order.getLogisticsException(), hasCompensation));
    }

    /** 在队列里 = 有来源 ∧ 用户未注销（O-26）。 */
    private boolean inQueue(ShopOrder order) {
        return source(order).isPresent() && !userDeleted(order);
    }

    private boolean userDeleted(ShopOrder order) {
        return accountDeletions.findByUserId(order.getUserId()).isPresent();
    }

    /**
     * 整单取消的落库计划：来源 + 类别 → 取消原因 / 动作（{@link CancelCategoryMapping}，不符 409）。
     * 揽收失败（{@link CancelCategoryMapping.Action#PICKUP_CANCEL}）由 Controller 在<b>事务外</b>调
     * {@code ShopLogisticsExceptionService.resolvePickupFailureByCancel}（先取消旧运单，失败则中止）。
     */
    @Transactional(readOnly = true)
    public CancelCategoryMapping.Plan planWhole(String orderToken, CancelCategoryMapping.Category category) {
        ShopOrder order = requireOrder(orderToken);
        requireInQueue(order);
        return CancelCategoryMapping.resolve(source(order).orElse(null), category, order.getStatus(),
                order.getReturnCause(), order.getReturnInboundAt() != null);
    }

    /**
     * 整单取消 / 赔付试算（D3~D5）：与落库同一计算（{@code ShopOrderRefundService.preview*}）。
     *
     * @param quote           未保价丢件破损尚未填协商额时为 null（页面只画上限与输入框）
     * @param inboundMissing  用户原因但退回商品尚未全部入库（D4：明细照显，按钮置灰，提交 409）
     * @param needsNegotiated 未保价丢件破损（须填协商额，≤ 商品实付）
     */
    public record WholePreview(CancelCategoryMapping.Plan plan, com.tailtopia.shop.order.pricing.RefundQuote quote,
            long payable, long shippingPaid, long insuranceFee, long goodsPaidCap, boolean insured,
            long refundedBefore, boolean inboundMissing, boolean needsNegotiated) {

        public boolean couponReturned() {
            return plan.reason().refundCoupon();
        }

        public boolean compensates() {
            return plan.reason().compensate();
        }

        /** 平台原因中回补库存的三类（快递原因 / 丢件破损不回补；用户原因已在入库时回补）。 */
        public boolean restocks() {
            CancelReason r = plan.reason();
            return r == CancelReason.OUT_OF_STOCK || r == CancelReason.WAYBILL_FAILED || r == CancelReason.PICKUP_FAILED;
        }
    }

    @Transactional(readOnly = true)
    public WholePreview previewWhole(String orderToken, CancelCategoryMapping.Category category,
            Long negotiatedAmount) {
        ShopOrder order = requireOrder(orderToken);
        requireInQueue(order);
        boolean inboundDone = order.getReturnInboundAt() != null;
        // 试算不因「未入库」拒绝（D4 要照显明细），落库时 planWhole 仍 409
        CancelCategoryMapping.Plan plan = CancelCategoryMapping.resolve(source(order).orElse(null), category,
                order.getStatus(), order.getReturnCause(), true);
        var snap = com.tailtopia.shop.order.pricing.RefundOrderSnapshot.of(order,
                orderLines.findByOrderIdOrderByIdAsc(order.getId()));
        boolean needsNegotiated = plan.reason() == CancelReason.LOST_DAMAGED && !snap.insuranceSelected();
        com.tailtopia.shop.order.pricing.RefundQuote quote = null;
        if (!needsNegotiated || negotiatedAmount != null) {
            quote = plan.action() == CancelCategoryMapping.Action.COMPENSATE
                    ? refundService.previewCompensate(orderToken, negotiatedAmount)
                    : refundService.previewWhole(orderToken, plan.reason(), negotiatedAmount);
        }
        return new WholePreview(plan, quote, snap.total(), snap.shippingPaid(), Math.max(0L, snap.insuranceFee()),
                snap.goodsPaid(), snap.insuranceSelected(), snap.refundedTotal(),
                plan.reason() == CancelReason.USER_FAULT_RETURNED && !inboundDone, needsNegotiated);
    }

    /** 部分取消试算（D6 / D7）：原因由来源绑定（只平台原因）；最后一件 {@code becameWhole}。 */
    @Transactional(readOnly = true)
    public com.tailtopia.shop.order.pricing.RefundQuote previewLine(String orderToken, long lineId, int qty) {
        ShopOrder order = requireOrder(orderToken);
        requireInQueue(order);
        return refundService.previewLine(orderToken, lineId, qty,
                CancelCategoryMapping.lineReason(source(order).orElse(null)));
    }

    // ---------- 入口：标记缺货 ----------

    /**
     * 标记缺货：待发货单进入异常订单工作台。原因必填（后台可见，不告知用户 ——
     * 用户在处置时才收到带原因的站内信，标记本身只是「这单要人来决定」）。
     */
    @Transactional
    public void flagShortage(String orderToken, String note, Long actorAccountId) {
        ShopOrder order = orders.findByPublicToken(orderToken)
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
        if (note == null || note.isBlank()) {
            throw AppException.validation("请填写缺货说明（缺哪个商品、差几件）")
                    .code("admin.err.orderException.flagNoteRequired");
        }
        String trimmed = note.trim();
        if (trimmed.length() > 200) {
            throw AppException.validation("缺货说明不能超过 200 字").code("admin.err.orderException.flagNoteTooLong");
        }
        order.flagShortage(actorAccountId, trimmed, java.time.Instant.now()); // 不兜底成 0：列上有 FK → admin_accounts
        orders.save(order);
        audit.record(actorAccountId, AuditActions.SHOP_ORDER_SHORTAGE_FLAGGED, "SHOP_ORDER", orderToken,
                "标记缺货：" + trimmed);
    }

    // ---------- 处置① 整单取消并退款 ----------

    /**
     * 整单取消并退款 / 签收后赔付（V1.4.2 shop-admin Story 5.1 · AD-9 · AC4 ~ AC9）。
     *
     * <p>先选原因类别，服务端按来源绑定取消原因（{@link CancelCategoryMapping}，不符 409）；类别决定退款、补偿、退券，
     * 运营不能单独开关。金额只在 {@code ShopRefundCalculator}，退款服务同一事务里回补库存 / 退币 / 出退款单 / 退券。
     * 已签收 / 已完成的丢件破损走 {@code compensate}（订单状态不变）。
     *
     * <p>🔴 揽收失败（{@code PICKUP_CANCEL}）<b>不能</b>从这里落库：须先在事务外取消旧运单，由 Controller 调
     * {@code ShopLogisticsExceptionService.resolvePickupFailureByCancel}，成功后调 {@link #auditPickupCancel}。
     *
     * @param negotiatedAmount 仅未保价丢件破损必填（≤ 商品实付）
     */
    @Transactional
    public Outcome cancelWholeOrder(String orderToken, CancelCategoryMapping.Category category,
            Long negotiatedAmount, String reason, Long actorAccountId) {
        requireReason(reason);
        CancelCategoryMapping.Plan plan = planWhole(orderToken, category);
        if (plan.action() == CancelCategoryMapping.Action.PICKUP_CANCEL) {
            throw new IllegalStateException("揽收失败取消须经 resolvePickupFailureByCancel（事务外先取消旧运单）");
        }
        ShopOrderRefundService.Outcome out = plan.action() == CancelCategoryMapping.Action.COMPENSATE
                ? refundService.compensate(orderToken, negotiatedAmount, reason.trim(), actorAccountId)
                : refundService.cancelWhole(orderToken, plan.reason(), negotiatedAmount, reason.trim(), actorAccountId);
        auditHandled(actorAccountId, orderToken, plan, out.refund(), reason);
        log.info("异常订单整单处置 token={} category={} reason={} coinRefunded={} cashDue={}", orderToken,
                plan.category(), plan.reason(), out.coinRefunded(), out.cashRefundDue());
        return new Outcome(out.coinRefunded(), 0L, out.cashRefundDue());
    }

    /** 平台原因整单取消的简写（存量调用方沿用；仍按来源绑定原因，不符 409）。 */
    @Transactional
    public Outcome cancelWholeOrder(String orderToken, String reason, Long actorAccountId) {
        return cancelWholeOrder(orderToken, CancelCategoryMapping.Category.PLATFORM, null, reason, actorAccountId);
    }

    /** 揽收失败取消成功后补写处置审计（类别、退款合计、币现拆分、退券、库存回补），并返回结果供 toast。 */
    @Transactional
    public Outcome auditPickupCancel(String orderToken, String reason, Long actorAccountId) {
        ShopOrder order = requireOrder(orderToken);
        var refund = refunds.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()).stream()
                .filter(r -> r.getScope() == com.tailtopia.shop.order.refund.RefundScope.WHOLE)
                .reduce((a, b) -> b).orElse(null);
        auditHandled(actorAccountId, orderToken, new CancelCategoryMapping.Plan(CancelCategoryMapping.Category.PLATFORM,
                CancelReason.PICKUP_FAILED, CancelCategoryMapping.Action.PICKUP_CANCEL), refund, reason);
        return new Outcome(refund == null ? 0L : refund.getCoinRefund(), 0L,
                refund == null ? 0L : refund.getCashRefund());
    }

    /** AC13：审计记类别、退款合计、币现拆分、退券、库存回补（门店返佣按 O-23 不记）。 */
    private void auditHandled(Long actor, String orderToken, CancelCategoryMapping.Plan plan,
            com.tailtopia.shop.order.refund.ShopOrderRefund refund, String reason) {
        CancelReason r = plan.reason();
        boolean restock = r == CancelReason.OUT_OF_STOCK || r == CancelReason.WAYBILL_FAILED
                || r == CancelReason.PICKUP_FAILED;
        audit.record(actor, AuditActions.SHOP_ORDER_EXCEPTION_HANDLED, "SHOP_ORDER", orderToken,
                "%s：类别=%s；原因=%s；退款合计=%d（币 %d / 现金 %d）；退券=%s；库存回补=%s；退款单=%s；说明=%s".formatted(
                        plan.action() == CancelCategoryMapping.Action.COMPENSATE ? "签收后赔付" : "整单取消并退款",
                        plan.category(), r, refund == null ? 0L : refund.getTotalRefund(),
                        refund == null ? 0L : refund.getCoinRefund(), refund == null ? 0L : refund.getCashRefund(),
                        r.refundCoupon() ? "是" : "否", restock ? "是" : "否",
                        refund == null ? "—" : refund.getPublicToken(), reason == null ? "—" : reason.trim()));
    }

    // ---------- 处置② 部分取消 ----------

    /**
     * 部分取消（V1.4.2 shop-admin 5.1 · AD-9 · AC11）：原因由来源绑定（只平台原因：缺货 / 运单失败 / 揽收失败），
     * 有活动运单时拒绝（O-21，须先取消运单）；最后一行转整单时类别只能是平台原因（即此处的绑定原因）。
     * 金额只在 {@code ShopRefundCalculator}（满减按适用行分摊、币段按比例向下取整、零头入现金）。
     */
    @Transactional
    public void cancelLine(String orderToken, long lineId, int qty, String reason,
            Long actorAccountId) {
        ShopOrder order = requireOrder(orderToken);
        requireInQueue(order);
        requireReason(reason);
        if (qty <= 0) {
            throw AppException.validation("取消数量必须为正").code("admin.err.orderException.qtyPositive");
        }
        orderLines.findById(lineId)
                .filter(l -> l.getOrderId().equals(order.getId()))
                .orElseThrow(() -> AppException.notFound("订单行不存在").code("admin.err.order.lineNotFound"));
        CancelReason cancelReason = CancelCategoryMapping.lineReason(source(order).orElse(null));
        if (!shipments.findActiveByShopOrderId(order.getId()).isEmpty()) {
            throw AppException.conflict("该订单有活动运单，请先取消运单再部分取消")
                    .code("admin.err.v142.exception.activeWaybill");
        }

        ShopOrderRefundService.Outcome out = refundService.cancelLine(orderToken, lineId, qty,
                cancelReason, reason.trim(), actorAccountId);

        audit.record(actorAccountId, AuditActions.SHOP_ORDER_EXCEPTION_HANDLED, "SHOP_ORDER",
                orderToken,
                "部分取消：行=%d 数量=%d；类别=PLATFORM；原因=%s；退款合计=%d（币 %d / 现金 %d）；退券=%s；库存回补=是；退款单=%s；说明=%s"
                        .formatted(lineId, qty, cancelReason, out.refund().getTotalRefund(), out.coinRefunded(),
                                out.cashRefundDue(), out.refund().getScope() == com.tailtopia.shop.order.refund.RefundScope.WHOLE
                                        ? "是" : "否", out.refund().getPublicToken(), reason.trim()));
    }

    // ---------- 处置③ 联系用户后继续 ----------

    /**
     * 联系用户后继续履约：<b>不动状态、不动库存、不动钱</b>，只留痕与告知。
     *
     * <p>这条出口存在的意义是让「已经打过电话、用户同意等」这件事在系统里有记录 ——
     * 否则下一个客服看到同一张异常单，会以为没人处理过而再打一次。
     */
    @Transactional
    public void contactAndContinue(String orderToken, String reason, Long actorAccountId) {
        ShopOrder order = requireOrder(orderToken);
        requireInQueue(order);
        requireReason(reason);
        // V1.4.2 shop-admin 5.1 · AD-9：「联系用户后继续」只对缺货来源（语义不变：清缺货标记）
        if (source(order).orElse(null) != ExceptionSource.OUT_OF_STOCK) {
            throw AppException.conflict("只有缺货订单可以联系用户后继续").code("admin.err.v142.exception.continueOnlyShortage");
        }
        // 继续履约 = 这单的缺货处置完了（补到货 / 用户同意等）→ 离开工作台
        order.clearShortageFlag();
        orders.save(order);
        audit.record(actorAccountId, AuditActions.SHOP_ORDER_EXCEPTION_HANDLED, "SHOP_ORDER",
                orderToken, "联系用户后继续履约：" + reason);
        notifyUser(order, "关于你的订单，我们已与你沟通并将继续为你发货。说明：" + reason);
    }

    // ---------- 异常视图 ----------

    /**
     * 异常订单候选：已付款待发货、且<b>运营已标记缺货</b>。
     *
     * <p>⚠️ 这不是一份「系统检测到的 bug 清单」，而是一份<b>待人工判断的清单</b>（S-3）。
     */
    @Transactional(readOnly = true)
    public List<ShopOrder> exceptionCandidates(int limit) {
        // V1.3.0 Story 10.1 AC3：工作台左栏**先进先出**（时间升序）——积压最久的那一单最该先处置。
        // V1.4.2 shop-admin 5.1 · AD-9：粗筛后按 ExceptionSource 一个函数判定（含已签收且无赔付单）；注销用户不进（O-26）
        return orders.findExceptionQueue(ShopOrderStatus.PENDING_SHIPMENT, ShopOrderStatus.SHIPPED,
                        ShopOrderStatus.DELIVERED, ShopOrderStatus.COMPLETED,
                        com.tailtopia.shop.order.domain.LogisticsException.PICKUP_FAILED,
                        com.tailtopia.shop.order.domain.LogisticsException.RETURNED,
                        com.tailtopia.shop.order.domain.LogisticsException.LOST,
                        com.tailtopia.shop.order.domain.LogisticsException.DAMAGED,
                        com.tailtopia.shop.order.refund.RefundScope.COMPENSATION,
                        org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit))).stream()
                .filter(this::inQueue).toList();   // 库内条件与 ExceptionSource 一致，这里只是兜底
    }

    /** V1.4.2 shop-admin Story 5.3：待办中心「异常订单」角标 = 队列「待处理」条数（注销用户不计，O-26）。 */
    @Transactional(readOnly = true)
    public long queueCount() {
        return orders.countExceptionQueue(ShopOrderStatus.PENDING_SHIPMENT, ShopOrderStatus.SHIPPED,
                ShopOrderStatus.DELIVERED, ShopOrderStatus.COMPLETED,
                com.tailtopia.shop.order.domain.LogisticsException.PICKUP_FAILED,
                com.tailtopia.shop.order.domain.LogisticsException.RETURNED,
                com.tailtopia.shop.order.domain.LogisticsException.LOST,
                com.tailtopia.shop.order.domain.LogisticsException.DAMAGED,
                com.tailtopia.shop.order.refund.RefundScope.COMPENSATION);
    }

    /**
     * 这一批订单里哪些还挂着异常（V1.3.0 Story 10.2 AC1：B15 列表的「异常」标）。
     *
     * <p>🔴 <b>不能拿 {@link #exceptionCandidates} 的结果来求交</b>：那份候选集是
     * <b>最旧的 N 条</b>待发货单（A8 的先进先出口径），而 B15 列表是<b>下单时间倒序</b>的最新 20 条 ——
     * 待发货总数一超过那个 N，两个集合零交集，第一页就<b>永远不会出现「异常」标</b>，
     * 而这件事在界面上和「真的没有异常」一模一样。
     *
     * <p>所以按<b>本页这几条</b>逐个判，并先用状态短路：只有 {@code PENDING_SHIPMENT} 才可能是异常，
     * 其余状态一次库存查询都不发。
     */
    @Transactional(readOnly = true)
    public java.util.Set<String> flagged(java.util.Collection<String> orderTokens) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String token : orderTokens) {
            if (isCandidate(token)) {
                out.add(token);
            }
        }
        return out;
    }

    /**
     * 某一单还在不在候选集里（V1.3.0 Story 10.1）。
     *
     * <p>候选集没有「已处理」页签：处置（整单取消 / 继续履约）会清掉缺货标记，那一单就离开本页；
     * 处置记录在操作审计里（这正是 A8 只有一个页签的原因，见 story T0 重核 ③）。
     */
    @Transactional(readOnly = true)
    public boolean isCandidate(String orderToken) {
        return orders.findByPublicToken(orderToken).map(this::inQueue).orElse(false);
    }

    /**
     * 判据的唯一实现（V1.4.2 shop-admin 5.1 · AD-9）：有队列来源（{@link ExceptionSource#of}）∧ 用户未注销。
     * 列表「异常」标、工作台左栏、处置后去留都走它。
     */
    @Transactional(readOnly = true)
    public boolean isCandidate(ShopOrder order) {
        return inQueue(order);
    }

    /**
     * 一行的库存现状（V1.3.0 Story 10.1 AC3 ①「异常原因说明卡」），只读参考。
     *
     * @param insufficient 该 SKU 连库存记录都没有 —— 库存不变式下账面上唯一可能「发不出」的情形。
     *                     实物缺多少看运营标记时写的说明，不看这里。
     */
    public record LineStock(long lineId, long actual, long locked, int needed, boolean insufficient) {
    }

    /**
     * 订单各行的账面库存（只读，供说明卡与行表标注）。
     *
     * <p>⚠️ <b>不是候选判据</b>：{@code locked <= actual} 是库级不变式，账面数字永远「够发」；
     * 列出来是让运营对照实物（账面 12 / 已锁 5，仓里只剩 3 —— 差额就是要回查的那次盘点）。
     */
    @Transactional(readOnly = true)
    public List<LineStock> lineStocks(ShopOrder order) {
        List<LineStock> out = new java.util.ArrayList<>();
        for (ShopOrderLine line : orderLines.findByOrderIdOrderByIdAsc(order.getId())) {
            var row = inventory.findBySkuId(line.getSkuId()).orElse(null);
            long actual = row == null ? 0L : row.getActual();
            long locked = row == null ? 0L : row.getLocked();
            out.add(new LineStock(line.getId(), actual, locked, line.getQty(), row == null));
        }
        return List.copyOf(out);
    }

    // ---------- 内部 ----------

    private ShopOrder requireOrder(String orderToken) {
        return orders.findByPublicToken(orderToken)
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
    }

    /** 只处置队列里的单（来源为空 / 已出队 / 用户已注销 → 409）。 */
    private void requireInQueue(ShopOrder order) {
        if (!inQueue(order)) {
            throw AppException.conflict("该订单不在异常订单队列").code("admin.err.v142.exception.notInQueue");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            // 🔴 原因必填：站内信要把它告诉用户，审计要靠它复盘。
            //    「无原因取消」在用户那边就是一条毫无解释的坏消息。
            throw AppException.validation("请填写处置原因（会一并告知用户）").code("admin.err.orderException.reasonRequired");
        }
    }

    /** 🔒 站内信只说原因与致歉，不带金额明细、不带收件信息。 */
    private void notifyUser(ShopOrder order, String body) {
        try {
            notifications.send(order.getUserId(), NotificationType.SHOP_ORDER_EXCEPTION,
                    "订单有变动", body, NotificationType.SHOP_ORDER_EXCEPTION.name(),
                    order.getPublicToken());
        } catch (RuntimeException e) {
            log.warn("异常订单站内信发送失败（不回滚处置）token={} cause={}",
                    order.getPublicToken(), e.getClass().getSimpleName());
        }
    }

    /**
     * 处置结果。
     *
     * @param compensationPremium 恒 0（v1.4.2 shop 4.1：币段不再发补偿；现金段选 PawCoin 时由退款服务发放）
     * @param cashRefundDue       现金段 —— 已生成取消退款单，待用户选择去向
     */
    public record Outcome(long coinRefunded, long compensationPremium, long cashRefundDue) {
    }
}
