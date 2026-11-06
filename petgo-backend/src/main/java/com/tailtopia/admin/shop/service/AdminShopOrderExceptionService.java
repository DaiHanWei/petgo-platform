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

    public AdminShopOrderExceptionService(ShopOrderRepository orders,
            ShopOrderLineRepository orderLines,
            com.tailtopia.shop.repository.SkuInventoryRepository inventory,
            ShopOrderRefundService refundService,
            AdminAuditService audit, NotificationService notifications) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.inventory = inventory;
        this.refundService = refundService;
        this.audit = audit;
        this.notifications = notifications;
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
     * 整单取消并退款（v1.4.2 shop Story 4.1 AC7.1 改走 {@link ShopOrderRefundService}）。
     *
     * <p>🔴 A8 工作台只处理缺货：原因固定 {@link CancelReason#OUT_OF_STOCK}，运营填的文字是说明。
     * 状态由原来的 {@code PENDING_SHIPMENT → CANCELLED} 改为 {@code → REFUNDING →（全部退款单完成）REFUNDED}；
     * 库存回补、币段即时退回、<b>现金段生成退款单待用户选去向</b>（不再只记审计）、券按原因退回都在退款服务同一事务里。
     * 币段不再发补偿：补偿只对「现金段选退为 PawCoin」的金额发放（PRD 11-05），在用户选去向时由退款服务发放。
     *
     * <p>🔴 <b>全额退款含运费</b>：货是平台没备够，用户不该为一次没发生的配送付钱。
     */
    @Transactional
    public Outcome cancelWholeOrder(String orderToken, String reason, Long actorAccountId) {
        requireHandleable(orderToken);
        requireReason(reason);
        ShopOrderRefundService.Outcome out = refundService.cancelWhole(orderToken, CancelReason.OUT_OF_STOCK,
                reason.trim(), actorAccountId);
        audit.record(actorAccountId, AuditActions.SHOP_ORDER_EXCEPTION_HANDLED, "SHOP_ORDER",
                orderToken,
                "整单取消并退款：原因=%s；PawCoin 段退回=%d；待用户选择去向的现金段=%d；退款单=%s"
                        .formatted(reason, out.coinRefunded(), out.cashRefundDue(),
                                out.refund() == null ? "—" : out.refund().getPublicToken()));
        log.info("异常订单整单取消 token={} coinRefunded={} cashDue={}", orderToken,
                out.coinRefunded(), out.cashRefundDue());
        return new Outcome(out.coinRefunded(), 0L, out.cashRefundDue());
    }

    // ---------- 处置② 部分取消 ----------

    /**
     * 部分取消：取消指定行的若干件（v1.4.2 shop Story 4.1 AC7.2 改走 {@link ShopOrderRefundService}，<b>开始退钱</b>）。
     *
     * <p>金额只在 {@code ShopRefundCalculator}（满减按适用行分摊、币段按比例、零头入现金）；该行库存回补、
     * 订单状态不变；取消到最后一件按整单处理。
     *
     * <p>✅ S-2：一单多包时以 {@code shipments} 为粒度处理，订单状态按「所有包裹」聚合判定
     * （见 {@code ShopOrderFulfillmentService}）—— 部分取消不改变这条聚合规则。
     */
    @Transactional
    public void cancelLine(String orderToken, long lineId, int qty, String reason,
            Long actorAccountId) {
        ShopOrder order = requireHandleable(orderToken);
        requireReason(reason);
        if (qty <= 0) {
            throw AppException.validation("取消数量必须为正").code("admin.err.orderException.qtyPositive");
        }
        orderLines.findById(lineId)
                .filter(l -> l.getOrderId().equals(order.getId()))
                .orElseThrow(() -> AppException.notFound("订单行不存在").code("admin.err.order.lineNotFound"));

        ShopOrderRefundService.Outcome out = refundService.cancelLine(orderToken, lineId, qty,
                CancelReason.OUT_OF_STOCK, reason.trim(), actorAccountId);

        audit.record(actorAccountId, AuditActions.SHOP_ORDER_EXCEPTION_HANDLED, "SHOP_ORDER",
                orderToken,
                "部分取消：行=%d 数量=%d 原因=%s；退款=%d（币 %d / 现金 %d）；退款单=%s"
                        .formatted(lineId, qty, reason, out.refund().getTotalRefund(), out.coinRefunded(),
                                out.cashRefundDue(), out.refund().getPublicToken()));
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
        ShopOrder order = requireHandleable(orderToken);
        requireReason(reason);
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
        return orders.findByStatusAndShortageFlaggedAtIsNotNullOrderByCreatedAtAscIdAsc(
                ShopOrderStatus.PENDING_SHIPMENT,
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)));
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
        return orders.findByPublicToken(orderToken)
                .map(AdminShopOrderExceptionService::isCandidate)
                .orElse(false);
    }

    /** 判据的唯一实现：待发货 ∧ 已标记缺货。列表「异常」标、工作台左栏、处置后去留都走它。 */
    static boolean isCandidate(ShopOrder order) {
        return order.getStatus() == ShopOrderStatus.PENDING_SHIPMENT && order.isShortageFlagged();
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

    private ShopOrder requireHandleable(String orderToken) {
        ShopOrder order = orders.findByPublicToken(orderToken)
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
        if (order.getStatus() != ShopOrderStatus.PENDING_SHIPMENT) {
            // 已发货的订单出口是退货（Epic 5），不是取消 —— 货已经出门了。
            throw AppException.conflict("只有待发货订单可走异常处置，当前状态：" + order.getStatus())
                    .code("admin.err.orderException.onlyPending", order.getStatus());
        }
        return order;
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
