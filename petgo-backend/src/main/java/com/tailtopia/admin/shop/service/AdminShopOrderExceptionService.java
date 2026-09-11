package com.tailtopia.admin.shop.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.service.InventoryMovementService;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 异常订单处置（Story 4.4，AB-11D / S-3）。
 *
 * <p>⚠️ <b>真正的超卖来源是盘点 / 报损 / 退货入库撤销，不是并发</b> —— 并发已由 Story 1.2 的
 * 条件原子写解决。这句话不只是注释：异常订单视图的说明文案也必须写明它，
 * 否则运营会一直以为这是系统 bug 而不去查自己刚做的那次盘点。
 *
 * <p>🔴 <b>S-3：运营手工选单取消，不做自动取消。</b>SKU ≤ 30、单量低，手工完全可行；
 * 自动取消会误杀大客户 —— 而被误杀的那一单往往正是最该保住的那一单。
 *
 * <p>🔴 <b>补偿是平台责任的兑现，不是客服的善意。</b>已付款却无货是平台的错，
 * 补偿溢价读的是 AB-6A 的<b>平台责任补偿溢价</b>配置项（{@code compensation_premium_rate}），
 * 与激励溢价是两个独立配置项（C-9 / D-8）；共用同一数值会静默毁掉 AB-13A 的售后成本口径。
 * 比例由配置决定，<b>不给运营手工调金额的入口</b>（避免因人而异的议价）。
 *
 * <p>⚠️ <b>QRIS 段的真钱打款不在本 story</b>：资金链路属 Epic 5 Story 5.5
 * （{@code pay/refund} 的 PayoutChannel + 两段审批）。AD-10 明确实物流程与资金流程是两个东西，
 * 本 story <b>不自建第二条打款路径</b>。本类只负责 PawCoin 段的即时退回与补偿，
 * 并把待退现金额写进审计与订单，供 5.5 接手。
 */
@Service
public class AdminShopOrderExceptionService {

    private static final Logger log =
            LoggerFactory.getLogger(AdminShopOrderExceptionService.class);

    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    private final InventoryMovementService movements;
    private final com.tailtopia.shop.repository.SkuInventoryRepository inventory;
    private final PawCoinWalletService wallet;
    private final PawCoinConfigRepository pawcoinConfig;
    private final AdminAuditService audit;
    private final NotificationService notifications;

    public AdminShopOrderExceptionService(ShopOrderRepository orders,
            ShopOrderLineRepository orderLines, InventoryMovementService movements,
            com.tailtopia.shop.repository.SkuInventoryRepository inventory,
            PawCoinWalletService wallet, PawCoinConfigRepository pawcoinConfig,
            AdminAuditService audit, NotificationService notifications) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.movements = movements;
        this.inventory = inventory;
        this.wallet = wallet;
        this.pawcoinConfig = pawcoinConfig;
        this.audit = audit;
        this.notifications = notifications;
    }

    // ---------- 处置① 整单取消并退款 ----------

    /**
     * 整单取消并退款。
     *
     * <p>五个动作在<b>同一事务</b>里：状态 → 库存回补 → PawCoin 段退回 → 补偿溢价 → 审计。
     * 拆开做的话，中间崩一次就会留下「订单已取消但币没退」这种只能人工对账才能发现的状态。
     *
     * <p>🔴 <b>全额退款含运费</b>：货是平台没备够，用户不该为一次没发生的配送付钱。
     */
    @Transactional
    public Outcome cancelWholeOrder(String orderToken, String reason, Long actorAccountId) {
        ShopOrder order = requireHandleable(orderToken);
        requireReason(reason);

        // ① 库存回补到 actual —— 走退货入库批次（S-9 / SPEC-11：采购单号填原订单号，
        //    单价由系统取该 SKU 最近一次采购价）。留原因就是留原订单号。
        List<ShopOrderLine> lines = orderLines.findByOrderIdOrderByIdAsc(order.getId());
        restock(order, lines, actorAccountId);
        for (ShopOrderLine line : lines) {
            int remaining = line.getQty() - line.getRefundedQty();
            if (remaining > 0) {
                line.addRefundedQty(remaining);
                orderLines.save(line);
            }
        }

        // ② PawCoin 段全额退回 + 补偿溢价
        long coinRefunded = refundCoinSegment(order, remainingCoin(order), "whole");
        long premium = payCompensationPremium(order, coinRefunded, "whole");

        // ③ 记账：已退回的部分（现金段待 Epic 5 打款，不在这里记为已退）
        order.recordRefund(coinRefunded, coinRefunded);
        order.transitionTo(ShopOrderStatus.CANCELLED);
        orders.save(order);

        long cashDue = order.getCashAmount() == null ? 0L : order.getCashAmount();
        audit.record(actorAccountId, AuditActions.SHOP_ORDER_EXCEPTION_HANDLED, "SHOP_ORDER",
                orderToken,
                "整单取消并退款：原因=%s；PawCoin 段退回=%d；补偿溢价=%d；待退现金段=%d（走 Epic 5 打款）"
                        .formatted(reason, coinRefunded, premium, cashDue));
        notifyUser(order, "很抱歉，你的订单无法履约，我们已为你取消并安排退款。原因：" + reason);
        log.info("异常订单整单取消 token={} coinRefunded={} premium={}", orderToken,
                coinRefunded, premium);
        return new Outcome(coinRefunded, premium, cashDue);
    }

    // ---------- 处置② 部分取消 ----------

    /**
     * 部分取消：取消指定行的剩余数量，回补该行库存。
     *
     * <p>⚠️ <b>本 story 只做「货」这一半</b>：行被取消、库存回补、用户被告知。
     * 对应的<b>金额结算走 Epic 5 Story 5.2 的整数累计法</b>（AD-2，安全攸关）——
     * 按比例拆分退款在多次部分退款后必须能精确归零，那是一整条独立的资金精度约束，
     * 在这里凭直觉写一个"按行金额比例退"，正是 AD-2 要防的那种实现。
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
        ShopOrderLine line = orderLines.findById(lineId)
                .filter(l -> l.getOrderId().equals(order.getId()))
                .orElseThrow(() -> AppException.notFound("订单行不存在").code("admin.err.order.lineNotFound"));

        line.addRefundedQty(qty);
        orderLines.save(line);
        movements.receiveReturn(line.getSkuId(), qty, order.getPublicToken(), LocalDate.now(),
                actorAccountId == null ? 0L : actorAccountId);

        audit.record(actorAccountId, AuditActions.SHOP_ORDER_EXCEPTION_HANDLED, "SHOP_ORDER",
                orderToken,
                "部分取消：行=%d 数量=%d 原因=%s；金额结算走 Epic 5 整数累计法"
                        .formatted(lineId, qty, reason));
        notifyUser(order, "很抱歉，你的订单中有商品无法发出，我们已为你取消该部分。原因：" + reason);
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
        audit.record(actorAccountId, AuditActions.SHOP_ORDER_EXCEPTION_HANDLED, "SHOP_ORDER",
                orderToken, "联系用户后继续履约：" + reason);
        notifyUser(order, "关于你的订单，我们已与你沟通并将继续为你发货。说明：" + reason);
    }

    // ---------- 异常视图 ----------

    /**
     * 异常订单候选：已付款待发货、且至少一行的<b>实际库存不足以发出</b>。
     *
     * <p>⚠️ 这不是一份「系统检测到的 bug 清单」，而是一份<b>待人工判断的清单</b>（S-3）。
     */
    @Transactional(readOnly = true)
    public List<ShopOrder> exceptionCandidates(int limit) {
        // V1.3.0 Story 10.1 AC3：工作台左栏**先进先出**（时间升序）——积压最久的那一单最该先处置。
        return orders.findByStatusOrderByCreatedAtAscIdAsc(ShopOrderStatus.PENDING_SHIPMENT,
                        org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)))
                .stream()
                .filter(this::hasInsufficientStock)
                .toList();
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
     * <p>🔴 <b>本页是实时计算的候选清单，不是带状态字段的工单表</b> —— 处置完那一单就自然离开，
     * 没有「已处理」这个可查询的集合（这正是 A8 只有一个页签的原因，见 story T0 重核 ③）。
     * 处置后要判断「这条该留在左栏还是消失」，只能重算一次。
     */
    @Transactional(readOnly = true)
    public boolean isCandidate(String orderToken) {
        return orders.findByPublicToken(orderToken)
                .filter(o -> o.getStatus() == ShopOrderStatus.PENDING_SHIPMENT)
                .map(this::hasInsufficientStock)
                .orElse(false);
    }

    /**
     * 一行的库存现状（V1.3.0 Story 10.1 AC3 ①「异常原因说明卡」）。
     *
     * @param insufficient 这一行发不出去。判据见 {@link #hasInsufficientStock} 的注释
     */
    public record LineStock(long lineId, long actual, long locked, int needed, boolean insufficient) {
    }

    /**
     * 订单各行的库存现状（只读，供说明卡与行表标注）。
     *
     * <p>🔴 <b>这是缺货判据的唯一实现</b>：{@link #hasInsufficientStock} 也走它
     * （{@code anyMatch(LineStock::insufficient)}）。两处各写一份的话，
     * 以后给判据加一项（比如扣掉在途 / 预留量）只改一处，就会出现
     * 「左栏不再列这一单，右栏却照打缺货红标」——而且改动者不会有任何提示。
     */
    @Transactional(readOnly = true)
    public List<LineStock> lineStocks(ShopOrder order) {
        List<LineStock> out = new java.util.ArrayList<>();
        for (ShopOrderLine line : orderLines.findByOrderIdOrderByIdAsc(order.getId())) {
            var row = inventory.findBySkuId(line.getSkuId()).orElse(null);
            long actual = row == null ? 0L : row.getActual();
            long locked = row == null ? 0L : row.getLocked();
            out.add(new LineStock(line.getId(), actual, locked, line.getQty(),
                    row == null || actual < locked || actual < line.getQty()));
        }
        return List.copyOf(out);
    }

    /**
     * 判定「这一单发不出去」。
     *
     * <p>🔴 口径是 <b>{@code actual < locked}</b>，不是 {@code actual < 0}：
     * 实际库存低于已被订单锁定的量，就意味着有订单注定发不出货 —— 而 {@code actual} 本身
     * 通常仍是正数。用 {@code actual < 0} 判会漏掉绝大多数真实超卖。
     */
    private boolean hasInsufficientStock(ShopOrder order) {
        // ⚠️ 判据只在 lineStocks() 里写一次（V1.3.0 Story 10.1）：这里曾经是同一段逻辑的第二份抄写。
        return lineStocks(order).stream().anyMatch(LineStock::insufficient);
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

    /** 🔴 回补走退货入库批次：留下的「原因」就是原订单号（S-9 / SPEC-11）。 */
    private void restock(ShopOrder order, List<ShopOrderLine> lines, Long actorAccountId) {
        for (ShopOrderLine line : lines) {
            int remaining = line.getQty() - line.getRefundedQty();
            if (remaining > 0) {
                movements.receiveReturn(line.getSkuId(), remaining, order.getPublicToken(),
                        LocalDate.now(), actorAccountId == null ? 0L : actorAccountId);
            }
        }
    }

    private static long remainingCoin(ShopOrder order) {
        long coin = order.getCoinAmount() == null ? 0L : order.getCoinAmount();
        return Math.max(0L, coin - order.getRefundedCoin());
    }

    /**
     * 🔴 <b>PawCoin 段只能退回 PawCoin。</b>本类<b>不存在</b>任何把 Coin 段折成现金的方法 ——
     * 是能力缺席，不是权限判断（FR-100A 规则 1，安全攸关）。
     */
    private long refundCoinSegment(ShopOrder order, long coins, String scope) {
        if (coins <= 0) {
            return 0L;
        }
        wallet.credit(order.getUserId(), coins, PawCoinTxnType.REFUND, "SHOP_ORDER",
                order.getId(), "shop-exception:" + scope + ":" + order.getPublicToken());
        return coins;
    }

    /**
     * 平台责任补偿溢价（零新代码：读 AB-6A 配置 + 复用 {@code credit(..., BONUS, ...)}）。
     *
     * <p>🔴 读的是 {@code compensationPremiumRate}，<b>不是</b> {@code premiumRate}。
     * 两者共用同一数值是静默错误（C-9 / D-8）。
     */
    private long payCompensationPremium(ShopOrder order, long coinBase, String scope) {
        if (coinBase <= 0) {
            return 0L;
        }
        PawCoinConfig config = pawcoinConfig.findAll().stream().findFirst().orElse(null);
        if (config == null || config.getCompensationPremiumRate() <= 0) {
            return 0L;
        }
        long premium = coinBase * config.getCompensationPremiumRate() / 100;
        long cap = config.getCompensationPremiumCap();
        if (cap > 0) {
            premium = Math.min(premium, cap);
        }
        if (premium <= 0) {
            return 0L;
        }
        wallet.credit(order.getUserId(), premium, PawCoinTxnType.BONUS, "SHOP_ORDER",
                order.getId(), "shop-exception-premium:" + scope + ":" + order.getPublicToken());
        return premium;
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
     * @param cashRefundDue 待退现金段 —— 🔴 <b>由 Epic 5 Story 5.5 的资金链路打款</b>，本 story 不打
     */
    public record Outcome(long coinRefunded, long compensationPremium, long cashRefundDue) {
    }
}
