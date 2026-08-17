package com.tailtopia.shop.returns.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.returns.domain.ReturnLine;
import com.tailtopia.shop.returns.domain.ReturnRequest;
import com.tailtopia.shop.returns.domain.ReturnStatus;
import com.tailtopia.shop.returns.domain.ReturnType;
import com.tailtopia.shop.returns.repository.ReturnLineRepository;
import com.tailtopia.shop.returns.repository.ReturnRequestRepository;
import com.tailtopia.shop.service.ShopTokenGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退货申请（Story 5.1，FR-104A / C-12 / AD-5 / S-8）。
 *
 * <p>🔴 <b>「同订单只能有一张进行中申请」由库级部分唯一索引强制</b>
 * （{@code uq_return_requests_active_per_order}），本类<b>不加锁、也不靠先查后插</b>：
 * 应用层的「查一下有没有」与「插入」之间永远有窗口，两个并发请求都能查到「没有」。
 * 本类做的是把库级冲突翻译成用户看得懂的 409。
 *
 * <p>🔴 <b>部分退款期间订单主状态不变</b>（AD-5）。只有整单性质的两类
 * （拒收 / 发货前取消）会把订单推进 {@code REFUNDING}；行级退货完全不动订单状态，
 * 直到 {@link #settleOrderIfFullyRefunded} 判定全部行退完才回写 {@code REFUNDED}。
 */
@Service
public class ReturnRequestService {

    private static final Logger log = LoggerFactory.getLogger(ReturnRequestService.class);

    /** 凭证图上限（FR-104A）。 */
    public static final int MAX_EVIDENCE = 6;

    private final ReturnRequestRepository returns;
    private final ReturnLineRepository returnLines;
    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    private final ShopTokenGenerator tokens;

    public ReturnRequestService(ReturnRequestRepository returns, ReturnLineRepository returnLines,
            ShopOrderRepository orders, ShopOrderLineRepository orderLines,
            ShopTokenGenerator tokens) {
        this.returns = returns;
        this.returnLines = returnLines;
        this.orders = orders;
        this.orderLines = orderLines;
        this.tokens = tokens;
    }

    /**
     * 提交退货申请。
     *
     * @param selections 订单行 id → 要退的数量。🔴 行级，不是整单（FR-104A）
     */
    @Transactional
    public ReturnRequest submit(long userId, String orderToken, ReturnType type,
            Map<Long, Integer> selections, String reasonNote, List<String> evidenceKeys) {
        ShopOrder order = orders.findByPublicTokenAndUserId(orderToken, userId)
                .orElseThrow(() -> AppException.notFound("订单不存在"));
        requireReturnable(order, type);
        if (selections == null || selections.isEmpty()) {
            throw AppException.validation("请至少勾选一件要退的商品");
        }
        if (evidenceKeys != null && evidenceKeys.size() > MAX_EVIDENCE) {
            throw AppException.validation("凭证图最多 " + MAX_EVIDENCE + " 张");
        }
        // 🔴 质量问题必填凭证：没有凭证的「质量问题」既无法质检也无法复盘，
        //    而它恰恰是平台承担运费 + 发补偿溢价的那一类。
        if (type == ReturnType.QUALITY_ISSUE && (evidenceKeys == null || evidenceKeys.isEmpty())) {
            throw AppException.validation("质量问题需要上传凭证图");
        }

        List<ShopOrderLine> lines = orderLines.findByOrderIdOrderByIdAsc(order.getId());
        Map<Long, ShopOrderLine> byId = new LinkedHashMap<>();
        lines.forEach(l -> byId.put(l.getId(), l));

        List<ReturnLine> pending = new ArrayList<>();
        long goodsRefund = 0;
        for (Map.Entry<Long, Integer> e : selections.entrySet()) {
            ShopOrderLine line = byId.get(e.getKey());
            if (line == null) {
                throw AppException.notFound("订单行不存在");
            }
            int qty = e.getValue() == null ? 0 : e.getValue();
            int remaining = line.getQty() - line.getRefundedQty();
            if (qty <= 0 || qty > remaining) {
                throw AppException.validation("退货数量不合法（该行剩余可退 " + remaining + "）");
            }
            requireLineReturnable(line, type);
            // 按下单时的行单价快照算 —— 商品改价不得改写历史订单该退多少
            long lineRefund = line.getUnitPrice() * (long) qty;
            goodsRefund += lineRefund;
            pending.add(ReturnLine.of(0L, line.getId(), qty, lineRefund));
        }

        boolean fullReturn = isFullReturn(lines, selections);

        ReturnRequest request = ReturnRequest.open(tokens.generate(), order.getId(), userId, type,
                fullReturn, reasonNote, joinKeys(evidenceKeys), order.getStatus());
        ReturnRequest saved;
        try {
            saved = returns.saveAndFlush(request);
        } catch (DataIntegrityViolationException e) {
            // 🔴 C-12 并发冲突：库级部分唯一索引把第二张挡在门外。
            //    翻译成明确的 409，而不是让 500 冒到用户面前。
            throw AppException.conflict("该订单已有进行中的退货申请");
        }
        for (ReturnLine l : pending) {
            returnLines.save(ReturnLine.of(saved.getId(), l.getOrderLineId(), l.getQty(),
                    l.getLineRefundAmount()));
        }

        // 🔴 只有整单性质的两类会推进订单主状态；行级退货【完全不动】订单状态（AD-5）
        if (type.skipsShipback()) {
            order.transitionTo(ShopOrderStatus.REFUNDING);
            orders.save(order);
        }
        log.info("退货申请已提交 return={} order={} type={} lines={} full={}",
                saved.getPublicToken(), orderToken, type, pending.size(), fullReturn);
        return saved;
    }

    /** S-8 ④：用户主动撤销 → 订单回到申请前的状态。 */
    @Transactional
    public ReturnRequest withdraw(long userId, String returnToken) {
        ReturnRequest r = returns.findByPublicTokenAndUserId(returnToken, userId)
                .orElseThrow(() -> AppException.notFound("退货申请不存在"));
        r.withdraw();
        returns.save(r);
        restoreOrderStatus(r);
        return r;
    }

    /**
     * 🔴 <b>SPEC-6 ②：驳回 / 撤销的回边</b> —— 订单回到申请前的状态。
     *
     * <p>只有当订单确实被本申请推进过（拒收 / 发货前取消）才需要回退；
     * 行级退货本来就没动订单状态，这里是 no-op。
     */
    @Transactional
    public void restoreOrderStatus(ReturnRequest r) {
        ShopOrder order = orders.findById(r.getShopOrderId()).orElse(null);
        if (order == null || order.getStatus() != ShopOrderStatus.REFUNDING) {
            return;
        }
        ShopOrderStatus before = r.getOrderStatusBefore();
        if (before == null) {
            return;
        }
        order.transitionTo(before);
        orders.save(order);
        log.info("退货申请结束，订单回到申请前状态 return={} status={}", r.getPublicToken(), before);
    }

    /**
     * 🔴 <b>仅当该订单全部行的退款均已完成，才回写订单为 {@code REFUNDED}</b>（AD-5）。
     *
     * <p>⚠️ 后台 PRD 原写「退款执行状态需与订单状态联动」—— 照字面实现会让退了一行的订单
     * 被整单标记为已退款，连带毁掉 AB-13A 售后成本与 AB-13D 对账。这个方法就是那条纠正。
     *
     * @return 本次是否把订单回写成了 REFUNDED
     */
    @Transactional
    public boolean settleOrderIfFullyRefunded(long orderId) {
        ShopOrder order = orders.findById(orderId).orElse(null);
        if (order == null || order.getStatus() == ShopOrderStatus.REFUNDED) {
            return false;
        }
        List<ShopOrderLine> lines = orderLines.findByOrderIdOrderByIdAsc(orderId);
        if (lines.isEmpty()) {
            return false;
        }
        boolean allRefunded = lines.stream().allMatch(l -> l.getRefundedQty() >= l.getQty());
        if (!allRefunded) {
            return false;   // 🔴 部分退货：订单主状态原样不动
        }
        order.transitionTo(ShopOrderStatus.REFUNDED);
        orders.save(order);
        return true;
    }

    /** 该订单当前是否已有进行中的申请（UX-DR3：订单详情页退货入口据此置灰）。 */
    @Transactional(readOnly = true)
    public boolean hasActiveRequest(long orderId) {
        return returns.findActiveByOrder(orderId).isPresent();
    }

    @Transactional(readOnly = true)
    public List<ReturnLine> linesOf(long returnRequestId) {
        return returnLines.findByReturnRequestIdOrderByIdAsc(returnRequestId);
    }

    /** S-7：超 7 日未寄回则关闭。逐笔独立事务，单笔失败不阻断其余。 */
    public int closeOverdueShipbacks(int limit) {
        int closed = 0;
        for (ReturnRequest r : returns.findShipbackOverdue(Instant.now(),
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)))) {
            try {
                if (closeOneOverdue(r.getId())) {
                    closed++;
                }
            } catch (RuntimeException e) {
                log.warn("关闭超时未寄回申请失败 id={} cause={}", r.getId(),
                        e.getClass().getSimpleName());
            }
        }
        return closed;
    }

    @Transactional
    public boolean closeOneOverdue(long id) {
        ReturnRequest r = returns.findById(id).orElse(null);
        if (r == null || !r.isShipbackOverdueAt(Instant.now())) {
            return false;
        }
        r.closeForNoShipback();
        returns.save(r);
        restoreOrderStatus(r);
        return true;
    }

    // ---------- 内部 ----------

    /**
     * 订单是否处于可申请退货的状态。
     *
     * <p>四类退货各有各的入口状态 —— 混成一个「已付款就能退」会让「发货前取消」
     * 在货已经在路上时还能被选中。
     */
    private void requireReturnable(ShopOrder order, ReturnType type) {
        ShopOrderStatus s = order.getStatus();
        boolean ok = switch (type) {
            case CANCEL_BEFORE_SHIPMENT -> s == ShopOrderStatus.PENDING_SHIPMENT;
            case REFUSED_ON_DELIVERY -> s == ShopOrderStatus.SHIPPED;
            // 🔴 普通退货须已签收，且在【签收起 7 日】的退货窗口内（SPEC-5 定义的起算点）。
            //    「已完成」不等于「不能再退」—— 自动确认收货不得没收退货权。
            case QUALITY_ISSUE, NON_QUALITY_ISSUE ->
                    (s == ShopOrderStatus.DELIVERED || s == ShopOrderStatus.COMPLETED)
                            && order.isWithinReturnWindow(Instant.now());
        };
        if (!ok) {
            throw AppException.conflict("当前订单状态不可申请该类型的退货：" + s);
        }
    }

    /**
     * 行级可退判定（FR-104）。
     *
     * <p>🔴 <b>「不可退」的行直接拒绝；「开封不退」的行在<b>非质量问题</b>下拒绝</b> ——
     * 质量问题（破损/临期/错发）与是否开封无关，把它一并挡掉等于让收到破损品的用户无路可走。
     * 前端会把这类行置灰不可勾选（Story 5.7），但服务端必须独立再判一次。
     */
    private static void requireLineReturnable(ShopOrderLine line, ReturnType type) {
        ReturnPolicy policy = line.getReturnPolicy();
        // 🔴 未知枚举降级到最保守档（HANDOFF 硬纪律 5）：宁可少承诺
        if (policy == null || policy == ReturnPolicy.NON_RETURNABLE) {
            throw AppException.conflict("该商品不支持退货：" + line.getProductName());
        }
        if (policy == ReturnPolicy.NO_RETURN_AFTER_OPEN
                && type == ReturnType.NON_QUALITY_ISSUE) {
            throw AppException.conflict("该商品开封后不支持退货：" + line.getProductName());
        }
    }

    /**
     * 是否整单退。
     *
     * <p>🔴 <b>由勾选范围自动得出</b>（C-12）：每一行的<b>剩余可退数量</b>都被选满才算整单退。
     * 这个判定直接决定去程运费退不退，是堵住「凑单免运 → 退掉凑单商品」套利的地方，
     * 所以它不能是一个可传入的布尔参数。
     */
    private static boolean isFullReturn(List<ShopOrderLine> lines, Map<Long, Integer> selections) {
        for (ShopOrderLine line : lines) {
            int remaining = line.getQty() - line.getRefundedQty();
            if (remaining <= 0) {
                continue;   // 这一行早已退净，不影响判定
            }
            Integer picked = selections.get(line.getId());
            if (picked == null || picked < remaining) {
                return false;
            }
        }
        return true;
    }

    private static String joinKeys(List<String> keys) {
        return keys == null || keys.isEmpty() ? null : String.join(",", keys);
    }
}
