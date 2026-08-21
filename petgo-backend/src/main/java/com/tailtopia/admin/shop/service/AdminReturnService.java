package com.tailtopia.admin.shop.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.returns.domain.OpenedPrecedent;
import com.tailtopia.shop.returns.domain.RejectDisposal;
import com.tailtopia.shop.returns.domain.ReturnLine;
import com.tailtopia.shop.returns.domain.ReturnRequest;
import com.tailtopia.shop.returns.domain.ReturnStatus;
import com.tailtopia.shop.returns.repository.OpenedPrecedentRepository;
import com.tailtopia.shop.returns.repository.ReturnRequestRepository;
import com.tailtopia.shop.returns.service.RefundExecutionService;
import com.tailtopia.shop.returns.service.ReturnRequestService;
import com.tailtopia.shop.service.InventoryMovementService;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台退货处理（Story 5.3 审核队列 AB-12A · 5.4 质检与入库 AB-12B · 5.6 判例库 AB-12D）。
 *
 * <p>🔴 <b>不新建审核通道</b>（AB-12A）：权限沿用既有退款审批三级
 * （{@code refund.view} / {@code refund.approve} / {@code refund.payout}），
 * 判定在控制器。新建一套平行的权限体系会让「谁能批退款」这个问题有两个互相矛盾的答案。
 *
 * <p>🔴 <b>「是否批准退货」与「运费由谁承担」分开记录</b>（AB-12A）——
 * 运费归属直接影响退款金额。本类里运费归属由 {@code ReturnType} / {@code isFullReturn}
 * <b>自动得出</b>，客服<b>没有</b>调整它的入口：手工可调等于打开凑单-退货套利的口子（C-12）。
 */
@Service
public class AdminReturnService {

    private static final Logger log = LoggerFactory.getLogger(AdminReturnService.class);

    private final ReturnRequestRepository returns;
    private final ReturnRequestService requests;
    private final RefundExecutionService refunds;
    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    private final InventoryMovementService movements;
    private final OpenedPrecedentRepository precedents;
    private final AdminAuditService audit;
    private final NotificationService notifications;

    public AdminReturnService(ReturnRequestRepository returns, ReturnRequestService requests,
            RefundExecutionService refunds, ShopOrderRepository orders,
            ShopOrderLineRepository orderLines, InventoryMovementService movements,
            OpenedPrecedentRepository precedents, AdminAuditService audit,
            NotificationService notifications) {
        this.returns = returns;
        this.requests = requests;
        this.refunds = refunds;
        this.orders = orders;
        this.orderLines = orderLines;
        this.movements = movements;
        this.precedents = precedents;
        this.audit = audit;
        this.notifications = notifications;
    }

    // ---------- 5.3 审核队列 ----------

    @Transactional(readOnly = true)
    public List<ReturnRequest> queue(ReturnStatus status, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, limit));
        return status == null
                ? returns.findAllByOrderByCreatedAtDescIdDesc(page)
                : returns.findByStatusOrderByCreatedAtAscIdAsc(status, page);
    }

    @Transactional(readOnly = true)
    public ReturnRequest require(String returnToken) {
        return returns.findByPublicToken(returnToken)
                .orElseThrow(() -> AppException.notFound("退货申请不存在").code("admin.err.return.notFound"));
    }

    /**
     * 批准。拒收 / 发货前取消<b>跳过寄回与质检</b>，直接进入退款执行。
     *
     * <p>🔴 本方法<b>没有运费归属参数</b> —— 那是刻意的（C-12 / AB-12A）：
     * 回程运费归属由退货类型得出，去程运费是否退由勾选范围得出，客服没有可调的旋钮。
     */
    @Transactional
    public ReturnRequest approve(String returnToken, long adminId) {
        ReturnRequest r = require(returnToken);
        r.approve(adminId);
        returns.save(r);
        audit.record(adminId, AuditActions.SHOP_RETURN_REVIEWED, "SHOP_RETURN", returnToken,
                "批准退货：类型=%s 整单退=%s 回程运费=%s 去程运费退回=%s".formatted(
                        r.getReturnType(), r.isFullReturn(), r.getReturnShipBearer(),
                        r.isOutboundFeeRefundable()));
        notifyUser(r, "你的退货申请已通过审核。");
        return r;
    }

    /** 驳回。🔴 理由必填并回告用户（复用 FR-52A）；订单回到申请前状态（SPEC-6 ②）。 */
    @Transactional
    public ReturnRequest reject(String returnToken, String reason, long adminId) {
        ReturnRequest r = require(returnToken);
        r.reject(adminId, reason);
        returns.save(r);
        requests.restoreOrderStatus(r);
        audit.record(adminId, AuditActions.SHOP_RETURN_REVIEWED, "SHOP_RETURN", returnToken,
                "驳回退货：" + reason);
        notifyUser(r, "你的退货申请未通过。原因：" + reason);
        return r;
    }

    /**
     * 🔴 后台手工创建退货申请时同样要校验「同订单仅一张进行中申请」（C-12）。
     *
     * <p>用户端入口层的拦截挡不住后台 —— 而后台恰恰是最可能绕过去的那一侧。
     * 这里做的是<b>前置提示</b>；真正的强制仍在库级部分唯一索引上。
     */
    @Transactional(readOnly = true)
    public void requireNoActiveRequest(long orderId) {
        if (requests.hasActiveRequest(orderId)) {
            throw AppException.conflict("该订单已有进行中的退货申请").code("admin.err.return.alreadyInProgress");
        }
    }

    // ---------- 5.4 质检与入库 ----------

    /**
     * 用户已寄回 → 登记运单，进入质检。
     *
     * <p>运费先由用户垫付；平台承担的情形在退款执行时按<b>实际运单金额</b>一并返还（S-7）。
     */
    @Transactional
    public ReturnRequest registerShipback(String returnToken, String carrier, String trackingNo,
            Long fee, long adminId) {
        ReturnRequest r = require(returnToken);
        r.registerShipback(carrier, trackingNo, fee);
        returns.save(r);
        // 🔒 运单号非 PII 可记；用户地址/电话不进摘要
        audit.record(adminId, AuditActions.SHOP_RETURN_INSPECTED, "SHOP_RETURN", returnToken,
                "登记寄回运单：%s %s".formatted(carrier, trackingNo));
        return r;
    }

    /**
     * 质检通过 → 触发<b>退货入库</b>并进入退款执行。
     *
     * <p>🔴 <b>只有质检通过的退货才进入可售库存</b>，且以<b>退货入库批次</b>入库
     * （与采购入库区分，二次销售风险不同）。S-9：采购单号填<b>原订单号</b>、
     * 进货单价取该 SKU <b>最近一次采购入库单价</b>，🔴 <b>不允许留空</b> ——
     * 留空会让「钱已退、货已回、系统里不存在」，并污染 AB-13C 的资金占用读数。
     */
    @Transactional
    public ReturnRequest passInspection(String returnToken, String note, String photoKeys,
            long adminId) {
        ReturnRequest r = require(returnToken);
        ShopOrder order = orders.findById(r.getShopOrderId()).orElseThrow();
        r.passInspection(note, photoKeys);
        returns.save(r);

        for (ReturnLine rl : requests.linesOf(r.getId())) {
            var line = orderLines.findById(rl.getOrderLineId()).orElseThrow();
            movements.receiveReturn(line.getSkuId(), rl.getQty(), order.getPublicToken(),
                    LocalDate.now(), adminId);
        }
        audit.record(adminId, AuditActions.SHOP_RETURN_INSPECTED, "SHOP_RETURN", returnToken,
                "质检通过并以退货入库批次入库（原订单号 %s）".formatted(order.getPublicToken()));
        notifyUser(r, "你寄回的商品已通过质检，退款正在处理。");
        return r;
    }

    /**
     * 质检不通过 → 驳回。
     *
     * <p>🔴 <b>S-10：{@code REJECTED} 不再是纯终态</b> —— 用户的货已经寄出来了，
     * 必须同时记下<b>处置方式</b>（退回用户 / 报损），并在用户端展示驳回原因 + 质检照片。
     * 选「退回用户」时<b>回寄运费由平台承担</b>：是平台判定驳回，不应再让用户付。
     *
     * <p>⚠️ <b>不通过的商品不进可售库存</b> —— 这正是「只有质检通过才入库」的另一半。
     */
    @Transactional
    public ReturnRequest failInspection(String returnToken, String note, String photoKeys,
            RejectDisposal disposal, String shipBackTrackingNo, long adminId) {
        ReturnRequest r = require(returnToken);
        if (disposal == RejectDisposal.RETURN_TO_USER
                && (shipBackTrackingNo == null || shipBackTrackingNo.isBlank())) {
            throw AppException.validation("选择「退回用户」时必须填写回寄单号").code("admin.err.return.trackingRequired");
        }
        r.failInspection(note, photoKeys, disposal, shipBackTrackingNo);
        returns.save(r);
        requests.restoreOrderStatus(r);
        audit.record(adminId, AuditActions.SHOP_RETURN_INSPECTED, "SHOP_RETURN", returnToken,
                "质检不通过：%s；处置=%s".formatted(note, disposal));
        notifyUser(r, "你寄回的商品质检未通过。原因：" + note);
        return r;
    }

    // ---------- 5.5 退款执行（后台入口） ----------

    @Transactional
    public RefundExecutionService.Outcome executeRefund(String returnToken, long adminId) {
        var out = refunds.execute(returnToken);
        audit.record(adminId, AuditActions.SHOP_RETURN_REFUNDED, "SHOP_RETURN", returnToken,
                "退款执行：PawCoin 段=%d 现金段=%d 补偿溢价=%d 激励溢价=%d 回程运费返还=%d"
                        .formatted(out.coinRefunded(), out.cashRefunded(),
                                out.compensationPremium(), out.incentivePremium(),
                                out.shipbackReimbursed()));
        ReturnRequest r = require(returnToken);
        notifyUser(r, "退款已处理完成。");
        return out;
    }

    @Transactional(readOnly = true)
    public RefundExecutionService.Quote quote(String returnToken) {
        return refunds.quote(returnToken);
    }

    // ---------- 5.6 判例库 ----------

    @Transactional
    public OpenedPrecedent addPrecedent(String situation, boolean judgedOpened, String rationale,
            String evidenceKeys, Long returnRequestId, long adminId) {
        OpenedPrecedent p = precedents.save(OpenedPrecedent.of(situation, judgedOpened, rationale,
                evidenceKeys, returnRequestId, adminId));
        audit.record(adminId, AuditActions.SHOP_RETURN_PRECEDENT_ADDED, "SHOP_PRECEDENT",
                String.valueOf(p.getId()),
                "沉淀开封判例：%s → %s".formatted(situation, judgedOpened ? "算开封" : "不算开封"));
        return p;
    }

    @Transactional(readOnly = true)
    public List<OpenedPrecedent> searchPrecedents(String q, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, limit));
        return q == null || q.isBlank()
                ? precedents.findAllByOrderByCreatedAtDescIdDesc(page)
                : precedents.search(q.trim(), page);
    }

    // ---------- 内部 ----------

    /** 🔒 站内信只说进展与原因，不带金额明细、不带地址。 */
    private void notifyUser(ReturnRequest r, String body) {
        try {
            notifications.send(r.getUserId(), NotificationType.SHOP_RETURN_UPDATED,
                    "退货进度更新", body, NotificationType.SHOP_RETURN_UPDATED.name(),
                    r.getPublicToken());
        } catch (RuntimeException e) {
            log.warn("退货进度站内信发送失败（不回滚处置）return={} cause={}",
                    r.getPublicToken(), e.getClass().getSimpleName());
        }
    }
}
